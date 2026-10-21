package com.multiship.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PR #536 — Renders raw carrier ZPL bytes to PNG by shelling out to the
 * bundled {@code zebrash-cli} Go binary. The binary is packaged inside
 * the JAR under {@code /native/{os}-{arch}/zebrash-cli[.exe]} (see
 * {@code native/zebrash-cli/}); on first use we extract to a per-JVM
 * temp file and invoke via {@link ProcessBuilder}. Mirrors the
 * {@code sqlite-jdbc} pattern for shipping native code inside a fat
 * JAR without external dependencies.
 *
 * <p>Why not pure Java: no maintained pure-Java ZPL renderer covers the
 * FedEx feature set (Code 128, PDF417, {@code ^GB} borders, {@code ^A0}
 * scalable fonts, {@code ^FH} escape sequences, {@code ^CI13} Unicode).
 * {@code ingridhq/zebrash} (Go, MIT) does; we wrap it in a 60-LoC CLI
 * and ship one binary per platform. See {@code memory/project_label_
 * preview_audit.md} for the investigation.
 *
 * <p>Concurrency: {@link #renderPng(byte[])} is thread-safe — each call
 * spawns a fresh process with private stdin/stdout. The one-time
 * extraction inside {@link #ensureExtracted()} is guarded by a
 * synchronized initialiser flag.
 */
@Slf4j
@Service
public class ZebrashRenderer {

    /** Wall-clock cap on a single render. Zebrash on a warm binary is
     *  ~100-250 ms for a 4×6" label; 5 s is a generous fault-detector,
     *  not a normal SLO. */
    @Value("${label.zebrash.timeout-seconds:5}")
    private int timeoutSeconds;

    /**
     * G3 — fail-closed knob for environments that MUST use the local
     * zebrash binary and never fall through to Labelary (so label
     * content never leaves the VPC). Default false matches historical
     * behaviour: boot continues with a warn-log if the binary is
     * absent, LabelaryRenderer serves the preview path. Prod flips to
     * true so a mis-packaged jar fails at startup, not silently at
     * first preview request. See {@code docs/zebrash-deploy.md}.
     */
    @Value("${label.require-zebrash:false}")
    private boolean requireZebrash;

    /**
     * Density override (dots-per-mm) for the rare shop whose stock isn't
     * 4" wide, so {@code ^PW}-based inference guesses the wrong density.
     * 0 (default) = auto-infer from {@code ^PW}. Set to 12 to force a
     * 300-dpi render for every label, 6 for 152-dpi, etc. See
     * {@code docs/zebrash-deploy.md}.
     */
    @Value("${label.zebrash.force-dpmm:0}")
    private int forceDpmm;

    /** Per-JVM extraction destination. Kept for the JVM's lifetime; on
     *  shutdown the OS reaps the temp file via {@link Path#toFile()}
     *  ({@code deleteOnExit}). Null until first extraction. */
    private volatile Path binaryPath;

    /** True when {@link #ensureExtracted()} has completed and
     *  {@link #binaryPath} is executable. Cheap flag so hot-path
     *  renders skip the synchronized block. */
    private volatile boolean ready;

    @PostConstruct
    void init() {
        // Try to extract eagerly at startup so the first /label/preview.png
        // request doesn't pay the extraction cost. Best-effort: a missing
        // binary for the current platform (e.g. developer laptop without
        // the resource baked in) surfaces on first render as
        // RendererUnavailableException, not at startup — keeps the app
        // bootable when the ZPL feature is off.
        try {
            ensureExtracted();
            log.info("ZebrashRenderer ready at {} (timeout={}s) — label content stays in the VPC.",
                    binaryPath, timeoutSeconds);
        } catch (RendererUnavailableException ex) {
            // G3 fail-closed: when label.require-zebrash=true, a
            // missing binary surfaces at BOOT, not silently on the
            // first preview request. Prod flips this on so a
            // mis-packaged jar (-Dzebrash.skip left as true) can't
            // route label content through Labelary.
            if (requireZebrash) {
                throw new IllegalStateException(
                        "ZebrashRenderer required but binary missing: " + ex.getMessage()
                        + " — rebuild with -Dzebrash.skip=false or flip label.require-zebrash=false "
                        + "to allow the Labelary fallback (see docs/zebrash-deploy.md).",
                        ex);
            }
            log.warn("ZebrashRenderer not available at startup: {}. "
                    + "LabelaryRenderer will serve the preview path (label content leaves the VPC). "
                    + "Build with -Dzebrash.skip=false to bundle the native binary; "
                    + "flip label.require-zebrash=true in prod so a missing binary fails startup.",
                    ex.getMessage());
        }
    }

    /**
     * Render {@code zpl} bytes to a 4×6" 8dpmm PNG. Blocks up to
     * {@link #timeoutSeconds} for the child process. Never returns
     * null or an empty array; failures throw.
     */
    private static final java.util.regex.Pattern LL_RE = java.util.regex.Pattern.compile("\\^LL(\\d+)");
    private static final java.util.regex.Pattern LH_RE = java.util.regex.Pattern.compile("\\^LH(\\d+),(\\d+)");
    private static final java.util.regex.Pattern FO_RE = java.util.regex.Pattern.compile("\\^F[OT](\\d+),(\\d+)");
    private static final java.util.regex.Pattern PW_RE = java.util.regex.Pattern.compile("\\^PW(\\d+)");
    private static final double MM_PER_INCH = 25.4;
    private static final double DOTS_PER_INCH = 203.2;
    private static final int STOCK_DOTS = 1218;
    /** Standard Zebra densities (dpmm): 6=152dpi, 8=203dpi, 12=300dpi, 24=600dpi. */
    private static final int[] STD_DPMM = {6, 8, 12, 24};

    /**
     * The render geometry zebrash-cli is invoked with: physical label
     * size in inches + print density. {@code widthIn × 25.4 × dpmm} is
     * the output canvas width in px, and ZPL {@code ^FO}/{@code ^PW}
     * coordinates land 1 dot = 1 px — so the canvas must be at least
     * {@code ^PW} dots wide at the ZPL's own density or content clips.
     */
    public record RenderSpec(double widthIn, double heightIn, int dpmm) {}

    /**
     * Pick the zebrash geometry for this ZPL.
     *
     * <p>A thermal printer renders the raw ZPL at its own native density,
     * so the preview only matches if the emulator renders at that same
     * density. We can't read the printer, but the ZPL's {@code ^PW}
     * (print width, in dots) pins it down: assuming the overwhelmingly
     * common 4" stock, {@code dpmm ≈ ^PW / (4 × 25.4)}, snapped to the
     * nearest standard density. A 203-dpi label (^PW≈812) stays on the
     * legacy 4"/8dpmm path byte-for-byte; a 300-dpi label (^PW≈1200)
     * renders at 12dpmm on a canvas wide enough to hold all 1200 dots
     * instead of clipping at 812.
     *
     * <p>{@code forceDpmm > 0} overrides the inference for the rare shop
     * whose stock isn't 4" wide (where ^PW alone is ambiguous).
     *
     * <p>ponytail: assumes 4"-wide stock. 6"-wide 203-dpi stock (^PW≈1219)
     * is misread as 300-dpi/4" — set {@code label.zebrash.force-dpmm} for
     * such a shop, or add a per-printer density if more than one needs it.
     */
    static RenderSpec computeSpec(byte[] zpl, int forceDpmm) {
        String text = new String(zpl, java.nio.charset.StandardCharsets.ISO_8859_1);
        Integer pw = parsePrintWidth(text);
        // Legacy 203-dpi path, unchanged: no ^PW to infer from, or ^PW
        // infers to 8dpmm (standard 4" thermal) with no override. Keeps
        // every existing 203-dpi label rendering identically.
        int inferred = (pw != null) ? snapDpmm(pw / (4.0 * MM_PER_INCH)) : 8;
        if (forceDpmm <= 0 && (pw == null || inferred == 8)) {
            return new RenderSpec(4.0, Double.parseDouble(canvasHeightInches(zpl)), 8);
        }
        int dpmm = forceDpmm > 0 ? forceDpmm : inferred;
        double dotsPerInch = dpmm * MM_PER_INCH;
        double widthIn = (pw != null) ? pw / dotsPerInch : 4.0;
        int neededDots = heightDots(text, dpmm);
        double heightIn = Math.min(8.0, Math.ceil(neededDots / dotsPerInch * 20) / 20.0);
        return new RenderSpec(widthIn, heightIn, dpmm);
    }

    /** Geometry for this ZPL applying the configured density override. */
    public RenderSpec renderSpecFor(byte[] zpl) {
        return computeSpec(zpl, forceDpmm);
    }

    static Integer parsePrintWidth(String text) {
        java.util.regex.Matcher m = PW_RE.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Snap a raw dpmm estimate to the nearest standard Zebra density. */
    static int snapDpmm(double raw) {
        int best = 8;
        double bestDiff = Double.MAX_VALUE;
        for (int d : STD_DPMM) {
            double diff = Math.abs(d - raw);
            if (diff < bestDiff) { bestDiff = diff; best = d; }
        }
        return best;
    }

    /** Needed canvas height in dots at {@code dpmm}: 6" stock unless the
     *  ZPL draws past it (same ^LL / ^LH / ^FO logic as {@link
     *  #canvasHeightInches}, but density-aware). */
    static int heightDots(String text, int dpmm) {
        int needed = (int) Math.round(6.0 * MM_PER_INCH * dpmm);
        java.util.regex.Matcher ll = LL_RE.matcher(text);
        if (ll.find()) needed = Math.max(needed, Integer.parseInt(ll.group(1)));
        int lhY = 0;
        java.util.regex.Matcher lh = LH_RE.matcher(text);
        if (lh.find()) lhY = Integer.parseInt(lh.group(2));
        int maxY = 0;
        java.util.regex.Matcher fo = FO_RE.matcher(text);
        while (fo.find()) maxY = Math.max(maxY, Integer.parseInt(fo.group(2)));
        if (maxY > 0) needed = Math.max(needed, maxY + lhY + 40);
        return needed;
    }

    /**
     * Label height for the render canvas, in inches, as a string for the CLI.
     * 6" unless the ZPL itself needs more: an explicit ^LL beyond the stock, or
     * fields positioned (plus the ^LH vertical offset and room for a line of
     * text) below the 6" edge. Rounded up to 0.05" and capped at 8" so a
     * malformed field can't request a metre-tall bitmap.
     */
    static String canvasHeightInches(byte[] zpl) {
        String text = new String(zpl, java.nio.charset.StandardCharsets.ISO_8859_1);
        int needed = STOCK_DOTS;
        java.util.regex.Matcher ll = LL_RE.matcher(text);
        if (ll.find()) needed = Math.max(needed, Integer.parseInt(ll.group(1)));
        int lhY = 0;
        java.util.regex.Matcher lh = LH_RE.matcher(text);
        if (lh.find()) lhY = Integer.parseInt(lh.group(2));
        int maxY = 0;
        java.util.regex.Matcher fo = FO_RE.matcher(text);
        while (fo.find()) maxY = Math.max(maxY, Integer.parseInt(fo.group(2)));
        if (maxY > 0) needed = Math.max(needed, maxY + lhY + 40);
        if (needed <= STOCK_DOTS) return "6";
        double inches = Math.min(8.0, Math.ceil(needed / DOTS_PER_INCH * 20) / 20.0);
        return String.format(java.util.Locale.ROOT, "%.2f", inches);
    }

    public byte[] renderPng(byte[] zpl) {
        if (zpl == null || zpl.length == 0) {
            throw new IllegalArgumentException("zpl bytes required");
        }
        ensureExtracted();

        // Geometry follows the ZPL's own density + extent so the preview
        // matches what the printer renders from the same raw ZPL. A
        // 300-dpi label (^PW≈1200) renders at 12dpmm on a 1200-dot-wide
        // canvas instead of clipping to 812; a 203-dpi label is unchanged.
        // Height still grows past 6" for ZPL that draws below the stock
        // edge (UPS's y≈1203 under ^LH10,12). Go parses these floats, so
        // the legacy "4"/"8" args map to identical output bytes.
        RenderSpec spec = renderSpecFor(zpl);
        ProcessBuilder pb = new ProcessBuilder(
                binaryPath.toString(),
                "--width", fmt(spec.widthIn()),
                "--height", fmt(spec.heightIn()),
                "--dpmm", Integer.toString(spec.dpmm()));
        // Explicit env prune — sidesteps macOS Spotlight quarantine and
        // avoids the child inheriting LC_ALL / TERM values that break
        // Go's default UTF-8 output handling.
        pb.environment().clear();
        pb.environment().put("PATH", System.getenv().getOrDefault("PATH", ""));

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException ex) {
            throw new RendererUnavailableException("Failed to spawn zebrash-cli: " + ex.getMessage(), ex);
        }

        // Feed stdin on a drainer thread + collect stdout / stderr on
        // two more. The classic ProcessBuilder deadlock trap is
        // waiting on the process while its pipes fill and block —
        // draining on side threads sidesteps it entirely.
        CompletableFuture<Void> stdinDone = CompletableFuture.runAsync(() -> {
            try (OutputStream out = proc.getOutputStream()) {
                out.write(zpl);
                out.flush();
            } catch (IOException ex) {
                log.warn("zebrash-cli stdin write failed: {}", ex.getMessage());
            }
        });
        CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> drainBytes(proc.getInputStream()));
        CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(() -> drainBytes(proc.getErrorStream()));

        boolean exited;
        try {
            exited = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RendererUnavailableException("Interrupted waiting for zebrash-cli", ex);
        }
        if (!exited) {
            proc.destroyForcibly();
            throw new RendererUnavailableException("zebrash-cli timed out after " + timeoutSeconds + "s");
        }

        // Ensure drainer threads have written their buffers before we
        // read them. CompletableFuture.join() propagates cancellation.
        try {
            stdinDone.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException ignore) {
            // stdin already closed with the process; if the write
            // didn't complete we'd see it in stderr / exit code.
        }
        byte[] stdout;
        byte[] stderr;
        try {
            stdout = stdoutFuture.get(1, TimeUnit.SECONDS);
            stderr = stderrFuture.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RendererUnavailableException("Failed to collect zebrash-cli output: " + ex.getMessage(), ex);
        }

        int exitCode = proc.exitValue();
        if (exitCode != 0) {
            String message = stderr.length > 0 ? new String(stderr) : "no stderr";
            throw switch (exitCode) {
                case 1 -> new RendererUnavailableException("zebrash-cli invocation error: " + message);
                case 2 -> new ZplParseException(message);
                case 3 -> new ZplRenderException(message);
                default -> new RendererUnavailableException(
                        "zebrash-cli exited " + exitCode + ": " + message);
            };
        }
        if (stdout.length == 0) {
            throw new ZplRenderException("zebrash-cli exited 0 with empty stdout");
        }
        // PNG magic bytes = 89 50 4E 47. Sanity-check so a corrupt
        // binary doesn't hand us garbage the FE tries to render.
        if (stdout.length < 4 || stdout[0] != (byte) 0x89 || stdout[1] != 0x50
                || stdout[2] != 0x4E || stdout[3] != 0x47) {
            throw new ZplRenderException("zebrash-cli produced non-PNG output ("
                    + stdout.length + " bytes)");
        }
        return stdout;
    }

    /**
     * Extract the platform-specific binary from the JAR into the JVM's
     * temp dir on first use. Idempotent: subsequent calls return
     * immediately after the {@link #ready} flag flips. Extraction is
     * SHA-256 keyed so a JAR upgrade with a new binary uses a fresh
     * temp file instead of overwriting one another JVM instance may
     * be executing.
     */
    private synchronized void ensureExtracted() {
        if (ready) return;
        String slug = platformSlug();
        String ext = slug.startsWith("windows") ? ".exe" : "";
        String resourcePath = "native/" + slug + "/zebrash-cli" + ext;
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new RendererUnavailableException(
                    "zebrash-cli binary not found on classpath at " + resourcePath
                            + ". Build it with `make -C native/zebrash-cli all` "
                            + "and copy dist/" + slug + "/ into "
                            + "backend/src/main/resources/native/" + slug + "/, "
                            + "or wait for the native-zebrash CI workflow to publish a release.");
        }
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String sha = sha256(bytes);
            Path dest = Path.of(System.getProperty("java.io.tmpdir"),
                    "zebrash-cli-" + sha.substring(0, 12) + ext);
            if (!Files.exists(dest)) {
                Files.copy(new java.io.ByteArrayInputStream(bytes), dest,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            // POSIX chmod 0755 — no-op on Windows, required on Linux/macOS.
            //noinspection ResultOfMethodCallIgnored
            dest.toFile().setExecutable(true, false);
            dest.toFile().deleteOnExit();
            this.binaryPath = dest;
            this.ready = true;
        } catch (IOException ex) {
            throw new RendererUnavailableException("Failed to extract zebrash-cli: " + ex.getMessage(), ex);
        }
    }

    /** Map JVM {@code os.name} + {@code os.arch} to the resource slug
     *  the CI workflow produces. Kept small — extend when we add
     *  freebsd/darwin-amd64/etc. */
    static String platformSlug() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String canonicalArch = switch (arch) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
        if (os.contains("win")) return "windows-" + canonicalArch;
        if (os.contains("mac") || os.contains("darwin")) return "darwin-" + canonicalArch;
        return "linux-" + canonicalArch;
    }

    /** Format inches for the CLI; trimmed precision, locale-independent. */
    private static String fmt(double inches) {
        return String.format(Locale.ROOT, "%.3f", inches);
    }

    private static byte[] drainBytes(InputStream in) {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new RendererUnavailableException("Failed to drain zebrash-cli stream: " + ex.getMessage(), ex);
        }
    }

    private static String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Thrown when the renderer itself can't start (missing binary,
     *  spawn failure, timeout). Callers should fall back to the JSX
     *  facsimile / synthetic ZPL builder. */
    public static class RendererUnavailableException extends RuntimeException {
        public RendererUnavailableException(String message) { super(message); }
        public RendererUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** Thrown on zebrash-cli exit code 2 — ZPL didn't parse. Usually
     *  means the input isn't ZPL (URL passthrough leaked, wrong
     *  content type). Fall back to facsimile. */
    public static class ZplParseException extends RuntimeException {
        public ZplParseException(String message) { super(message); }
    }

    /** Thrown on zebrash-cli exit code 3 — parsed but the draw/encode
     *  step failed. Typically an unsupported ZPL command combination
     *  or a malformed barcode payload. Fall back to facsimile. */
    public static class ZplRenderException extends RuntimeException {
        public ZplRenderException(String message) { super(message); }
    }
}
