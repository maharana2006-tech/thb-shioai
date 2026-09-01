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
            log.info("ZebrashRenderer ready at {} (timeout={}s)", binaryPath, timeoutSeconds);
        } catch (RendererUnavailableException ex) {
            log.warn("ZebrashRenderer not available at startup: {}. "
                    + "First render will retry extraction; enable label.render-carrier-zpl "
                    + "only when the binary is present under src/main/resources/native/.",
                    ex.getMessage());
        }
    }

    /**
     * Render {@code zpl} bytes to a 4×6" 8dpmm PNG. Blocks up to
     * {@link #timeoutSeconds} for the child process. Never returns
     * null or an empty array; failures throw.
     */
    public byte[] renderPng(byte[] zpl) {
        if (zpl == null || zpl.length == 0) {
            throw new IllegalArgumentException("zpl bytes required");
        }
        ensureExtracted();

        ProcessBuilder pb = new ProcessBuilder(
                binaryPath.toString(),
                "--width", "4",
                "--height", "6",
                "--dpmm", "8");
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
