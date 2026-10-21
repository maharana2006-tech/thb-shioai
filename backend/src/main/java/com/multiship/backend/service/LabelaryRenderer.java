package com.multiship.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R2-BUG-014 (run-6) — HTTP-based ZPL→PNG renderer backed by
 * labelary.com. Replaces the previous zebrash-first path on the
 * View Label preview flow (operator asked to drop the local
 * fallback; Labelary now serves every preview render).
 *
 * <p><b>Privacy ceiling:</b> Labelary receives label content on
 * every render — shipper + recipient addresses, tracking numbers,
 * PO/DEPT, weights, declared value. Fine for dev and sandbox. For
 * production flows that handle commercially-sensitive invoices (CI
 * dupes, named clients, exact billables), either front with a
 * self-hosted zebrash binary or ship labels redacted. ponytail:
 * single-vendor dependency; add a self-hosted ZPL→PNG service when
 * compliance mandates no label content leaves the VPC.
 *
 * <p>Rate limits: Labelary's free tier is ~5 req/sec. The hot
 * label-preview endpoint caches aggressively (60s private cache
 * header on the Spring side), so a steady operator browse rate
 * stays within budget. Transient 429 / 503 / IO errors retry up to
 * {@link #maxAttempts} times with linear backoff; the FE's error
 * branch still falls back to the JSX facsimile if every attempt
 * fails.
 */
@Slf4j
@Service
public class LabelaryRenderer {

    @Value("${label.labelary.enabled:true}")
    private boolean enabled;

    @Value("${label.labelary.base-url:https://api.labelary.com}")
    private String baseUrl;

    @Value("${label.labelary.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${label.labelary.dpmm:8}")
    private int dpmm;

    @Value("${label.labelary.label-width-inches:4}")
    private double widthInches;

    @Value("${label.labelary.max-attempts:3}")
    private int maxAttempts;

    @Value("${label.labelary.retry-backoff-ms:400}")
    private long retryBackoffMs;

    /** Labelary {@code X-Rotation} header: 0 | 90 | 180 | 270. The carrier
     *  ZPL does NOT encode a bottom-up origin (no {@code ^PO}/{@code ^FWR}
     *  anywhere in carrier output) — zebrash and a real Zebra both render it
     *  upright, and the zebrash-rendered pngData/pdfData columns and raw ZPL
     *  are un-rotated. A blanket 180° here only flipped the Labelary preview
     *  relative to the printed label, so the default is now 0 (no rotation).
     *  Still honored at 90/180/270 (guard below) as an escape hatch via
     *  {@code LABEL_LABELARY_ROTATION} for a future carrier-specific need. */
    @Value("${label.labelary.rotation:0}")
    private int rotation;

    /** @return {@code true} when Labelary is enabled by config and
     *  reachable at the configured base URL. */
    public boolean isAvailable() {
        return enabled;
    }

    /**
     * Render {@code zpl} bytes to a PNG via Labelary's public endpoint.
     * Blocks up to {@link #timeoutSeconds}. Never returns null; failures
     * throw {@link ZebrashRenderer.RendererUnavailableException}.
     *
     * <p>Label height follows the ZPL's own {@code ^LL} / {@code ^FO}
     * extent (same math as {@link ZebrashRenderer#canvasHeightInches}),
     * capped at 8" so a malformed {@code ^LL99999} can't request a
     * metre-tall bitmap.
     */
    public byte[] renderPng(byte[] zpl) {
        return render(zpl, "image/png");
    }

    /**
     * Render {@code zpl} bytes to a 4×6 PDF via Labelary. Same shape as
     * {@link #renderPng} — only the Accept header differs. Used by the
     * {@code /label/pdf} path when the stored carrier ZPL exists; the
     * carrier's own SHIP FROM burns in directly instead of being
     * reconstructed from DB fields (closes BUG-001 F-001).
     */
    public byte[] renderPdf(byte[] zpl) {
        return render(zpl, "application/pdf");
    }

    private byte[] render(byte[] zpl, String acceptType) {
        if (zpl == null || zpl.length == 0) {
            throw new IllegalArgumentException("zpl bytes required");
        }
        if (!enabled) {
            throw new ZebrashRenderer.RendererUnavailableException(
                    "Labelary renderer disabled (label.labelary.enabled=false)");
        }
        String heightInches = canvasHeightInches(zpl);
        String url = String.format("%s/v1/printers/%ddpmm/labels/%sx%s/0/",
                baseUrl.replaceAll("/$", ""), dpmm,
                formatDimension(widthInches), heightInches);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeoutSeconds)))
                .build();

        int attempts = Math.max(1, maxAttempts);
        String lastReason = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Accept", acceptType)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(zpl));
                if (rotation == 90 || rotation == 180 || rotation == 270) {
                    rb.header("X-Rotation", String.valueOf(rotation));
                }
                java.net.http.HttpRequest req = rb.build();
                java.net.http.HttpResponse<byte[]> resp = client.send(req,
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    byte[] body = resp.body();
                    if (body != null && body.length > 0) {
                        if (attempt > 1) {
                            log.info("Labelary succeeded on attempt {} of {}",
                                    attempt, attempts);
                        }
                        return body;
                    }
                    lastReason = "empty " + acceptType + " body";
                } else {
                    String snippet = resp.body() == null ? ""
                            : new String(resp.body(), StandardCharsets.UTF_8);
                    if (snippet.length() > 240) snippet = snippet.substring(0, 240) + "...";
                    lastReason = "HTTP " + status + ": " + snippet;
                    // Retry transient only (429, 5xx). 4xx (except 429) is a
                    // bad request — retrying won't help, fail fast.
                    if (status != 429 && status < 500) {
                        throw new ZebrashRenderer.RendererUnavailableException(
                                "Labelary " + lastReason);
                    }
                }
            } catch (java.io.IOException ex) {
                lastReason = "IO: " + ex.getMessage();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ZebrashRenderer.RendererUnavailableException(
                        "Labelary call interrupted", ex);
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(retryBackoffMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ZebrashRenderer.RendererUnavailableException(
                            "Labelary backoff interrupted", ie);
                }
            }
        }
        throw new ZebrashRenderer.RendererUnavailableException(
                "Labelary failed after " + attempts + " attempts: " + lastReason);
    }

    /** Mirror of {@link ZebrashRenderer#canvasHeightInches} so the two
     *  renderers produce the same label aspect for identical ZPL. */
    private static final Pattern LL_RE = Pattern.compile("\\^LL(\\d+)");
    private static final Pattern LH_RE = Pattern.compile("\\^LH(\\d+),(\\d+)");
    private static final Pattern FO_RE = Pattern.compile("\\^F[OT](\\d+),(\\d+)");
    private static final double DOTS_PER_INCH = 203.2;
    private static final int STOCK_DOTS = 1218;

    static String canvasHeightInches(byte[] zpl) {
        String text = new String(zpl, StandardCharsets.ISO_8859_1);
        int needed = STOCK_DOTS;
        Matcher ll = LL_RE.matcher(text);
        if (ll.find()) needed = Math.max(needed, Integer.parseInt(ll.group(1)));
        int lhY = 0;
        Matcher lh = LH_RE.matcher(text);
        if (lh.find()) lhY = Integer.parseInt(lh.group(2));
        int maxY = 0;
        Matcher fo = FO_RE.matcher(text);
        while (fo.find()) maxY = Math.max(maxY, Integer.parseInt(fo.group(2)));
        if (maxY > 0) needed = Math.max(needed, maxY + lhY + 40);
        if (needed <= STOCK_DOTS) return "6";
        double inches = Math.min(8.0, Math.ceil(needed / DOTS_PER_INCH * 20) / 20.0);
        return String.format(java.util.Locale.ROOT, "%.2f", inches);
    }

    private static String formatDimension(double d) {
        return d == Math.floor(d)
                ? String.valueOf((int) d)
                : String.format(java.util.Locale.ROOT, "%.1f", d);
    }
}
