package com.multiship.backend.service;

import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprint 52 PR B — resolves the carrier's canonical label artifact for
 * an order when the stored bytes match the format the caller wants
 * (ZPL or PDF). Read-only: no schema changes, no persistence changes.
 *
 * <p>Where the carrier's real label lives today: connectors stash
 * either a signed URL or base64-encoded bytes in
 * {@code order_label_tracking.label_file_path} (see
 * {@link com.multiship.backend.service.CarrierServiceImpl#generateManualLabel}
 * around line 580). There is no format column — this resolver
 * content-sniffs to decide whether to serve the stored artifact
 * verbatim or return empty so the caller falls back to a facsimile
 * ({@link ZplLabelService} / {@link PdfLabelService}).
 *
 * <p>Detection rules:
 * <ul>
 *   <li>{@code label_file_path} blank / null → empty (no artifact
 *       stored; facsimile fallback).</li>
 *   <li>Starts with {@code http://} or {@code https://} → fetch the
 *       URL with a short read timeout; sniff format from the response
 *       body's magic bytes.</li>
 *   <li>Else → try Base64 decode; sniff format from the decoded
 *       magic bytes. Falls to raw text (raw ZPL is stored plaintext
 *       when the carrier returned {@code ^XA...^XZ} as-is).</li>
 * </ul>
 *
 * <p>Format is compared against the requested format string:
 * {@code "ZPL"} matches magic {@code ^XA...} (raw ZPL); {@code "PDF"}
 * matches magic {@code %PDF-}. Mismatch → empty (facsimile fallback,
 * not a wrong-format error).
 */
@Service
@RequiredArgsConstructor
public class LabelArtifactResolver {

    private static final Logger log = LoggerFactory.getLogger(LabelArtifactResolver.class);

    /** Short read timeout on URL fetches — carriers' short-lived label URLs
     *  usually respond in &lt;500ms; anything longer suggests the URL has
     *  expired or the carrier's edge is slow. Fall back to facsimile
     *  rather than blocking the operator's download. */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(3);

    /** Byte prefixes used to detect format. `^XA` = raw ZPL start of block.
     *  `%PDF-` = ISO 32000 PDF signature. Kept as byte arrays because we
     *  don't want a String allocation to sniff 5 bytes. */
    private static final byte[] MAGIC_ZPL = new byte[]{'^', 'X', 'A'};
    private static final byte[] MAGIC_PDF = new byte[]{'%', 'P', 'D', 'F', '-'};

    private final OrderTrackingRepository orderTrackingRepository;
    private final com.multiship.backend.repository.LabelPackageRepository labelPackageRepository;

    private final RestClient http = RestClient.builder()
            .requestFactory(defaultRequestFactory())
            .build();

    /**
     * Single-package (or shipment-level) lookup — back-compat wrapper for
     * callers that don't (or can't) supply a package index.
     */
    public Optional<byte[]> resolveAsBytes(Integer orderNo, String desiredFormat) {
        return resolveAsBytes(orderNo, desiredFormat, null);
    }

    /**
     * PR #544 — Returns the carrier's stored artifact when it matches the
     * caller's desired format. Empty otherwise — the caller falls back to
     * a facsimile render.
     *
     * <p>When {@code pkgIndex} is supplied, reads per-package label from
     * {@link com.multiship.backend.model.LabelPackage} keyed by
     * {@code sequence_number}. When null, falls back to shipment-level
     * {@link OrderTracking} for backward-compat with single-package
     * orders (where {@code order_label_tracking} carries the only
     * label). Pre-PR-#544 this method was pkg-agnostic and always
     * returned pkg 1 on multi-package orders — pkg 2..N labels persisted
     * in {@code label_package} were unreachable via the endpoint.
     *
     * @param orderNo       the order whose label to look up
     * @param desiredFormat one of {@code "ZPL"} or {@code "PDF"}
     * @param pkgIndex      1-based package sequence; null for shipment-level
     */
    public Optional<byte[]> resolveAsBytes(Integer orderNo, String desiredFormat, Integer pkgIndex) {
        if (orderNo == null || !StringUtils.hasText(desiredFormat)) return Optional.empty();
        String normalized = desiredFormat.trim().toUpperCase(Locale.ROOT);

        // V143 fast path (ZPL-first Stage 2) — rows stamped after
        // 2026-10-11 carry pre-rendered ZPL/PDF/PNG in dedicated
        // columns. Zero conversion at serve time when the requested
        // format matches a populated column. Falls through to the
        // legacy label_file_path path when the column is null (pre-V143
        // orders, URL-only labels, or a conversion that failed at
        // generate time).
        if (pkgIndex != null && pkgIndex > 0) {
            Optional<com.multiship.backend.model.LabelPackage> pkg =
                    labelPackageRepository.findByOrderNoAndSequenceNumber(orderNo, pkgIndex);
            if (pkg.isEmpty()) return Optional.empty();
            Optional<byte[]> prerendered = readPrerendered(pkg.get(), normalized);
            if (prerendered.isPresent()) return prerendered;
        }

        String stored;
        if (pkgIndex != null && pkgIndex > 0) {
            // Per-package path: read the specific label_package row.
            // Returns empty when the pkg index doesn't exist (e.g. asking
            // for pkg 3 on a 2-pkg order) so callers 404 cleanly rather
            // than silently falling back to shipment-level (which would
            // return pkg 1 and mislead the operator).
            Optional<com.multiship.backend.model.LabelPackage> pkg =
                    labelPackageRepository.findByOrderNoAndSequenceNumber(orderNo, pkgIndex);
            if (pkg.isEmpty()) return Optional.empty();
            stored = pkg.get().getLabelFilePath();
        } else {
            // Back-compat shipment-level path (single-package orders).
            Optional<OrderTracking> row = orderTrackingRepository.findByOrderNo(orderNo);
            if (row.isEmpty()) return Optional.empty();
            stored = row.get().getLabelFilePath();
        }
        if (!StringUtils.hasText(stored)) return Optional.empty();

        boolean wasUrl = isUrl(stored);
        byte[] bytes = fetchBytes(stored);
        if (bytes == null || bytes.length == 0) return Optional.empty();

        String detected = sniffFormat(bytes);
        if (detected == null) return Optional.empty();
        if (!detected.equals(normalized)) return Optional.empty();

        // BUG-007 R7 — order #934118's label_file_path is a FedEx cache URL;
        // every /label fetch pays a 3-4s carrier round-trip + at some point
        // the URL expires. Replace the URL with the base64-encoded bytes so
        // subsequent reads are free + the artifact survives the URL expiry.
        // Shipment-level path only (pkgIndex=null); per-package rows go
        // through the pre-rendered column path which doesn't have this.
        if (wasUrl && pkgIndex == null) {
            cacheBytesInline(orderNo, bytes);
        }

        return Optional.of(bytes);
    }

    private static boolean isUrl(String stored) {
        String lower = stored.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Replace the carrier-URL label_file_path with the base64 of the fetched
     *  bytes so subsequent fetches skip the HTTP round-trip and survive the
     *  URL's expiry. Best-effort: a write failure is logged + ignored (the
     *  URL still works for now; we'll try again on the next read). */
    private void cacheBytesInline(Integer orderNo, byte[] bytes) {
        try {
            orderTrackingRepository.findByOrderNo(orderNo).ifPresent(row -> {
                row.setLabelFilePath(Base64.getEncoder().encodeToString(bytes));
                orderTrackingRepository.save(row);
                log.info("BUG-007: cached {} bytes inline for order {} (URL → base64).",
                        bytes.length, orderNo);
            });
        } catch (Exception e) {
            log.warn("BUG-007: inline-cache write failed for order {}: {}", orderNo, e.getMessage());
        }
    }

    /** V143 — read the pre-rendered column for the requested format.
     *  Empty if the column is null OR the stored value couldn't be
     *  decoded (returns empty rather than throwing — caller falls to
     *  the legacy label_file_path path). */
    private Optional<byte[]> readPrerendered(com.multiship.backend.model.LabelPackage pkg, String format) {
        String raw = switch (format) {
            case "ZPL" -> pkg.getZplData();
            case "PDF" -> pkg.getPdfData();
            case "PNG" -> pkg.getPngData();
            default -> null;
        };
        if (!StringUtils.hasText(raw)) return Optional.empty();
        try {
            if ("ZPL".equals(format)) return Optional.of(raw.getBytes());
            return Optional.of(Base64.getDecoder().decode(raw));
        } catch (IllegalArgumentException notBase64) {
            log.debug("LabelArtifactResolver: pre-rendered {} for order {} pkg {} not valid base64 — falling to legacy path",
                    format, pkg.getOrderNo(), pkg.getSequenceNumber());
            return Optional.empty();
        }
    }

    // ─── Fetch ──────────────────────────────────────────────────────────────

    /**
     * Turn a stored {@code label_file_path} into bytes:
     * URL → fetch; base64 → decode; raw text (starts with {@code ^XA}) →
     * treat as-is. Never throws — a failure returns null and the caller
     * falls to facsimile.
     */
    private byte[] fetchBytes(String stored) {
        String trimmed = stored.trim();
        // URL branch — signed carrier URLs. Short read timeout; failure
        // means expired URL or slow edge, both of which fall to facsimile.
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                return http.get().uri(trimmed).retrieve().body(byte[].class);
            } catch (Exception e) {
                log.debug("LabelArtifactResolver: URL fetch failed for {}: {}", trimmed, e.getMessage());
                return null;
            }
        }
        // Raw ZPL (some carriers persist plaintext ^XA...^XZ).
        if (startsWithAt(trimmed.getBytes(), MAGIC_ZPL, 0)) {
            return trimmed.getBytes();
        }
        // Base64 branch — try decode, fall back to raw text bytes if it
        // isn't valid base64 (some legacy rows stash the file path itself
        // as text). The subsequent format sniff makes the right call.
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException notBase64) {
            return trimmed.getBytes();
        }
    }

    // ─── Sniff ──────────────────────────────────────────────────────────────

    /**
     * Return the canonical format name ({@code "ZPL"} or {@code "PDF"})
     * detected from magic bytes, or {@code null} when the format isn't
     * one we serve as passthrough. Callers treat null as "fall back to
     * facsimile".
     */
    /** GIF87a / GIF89a — UPS's default LabelImageFormat comes back this way. */
    private static final byte[] MAGIC_GIF = "GIF8".getBytes();
    /** PNG signature — some carriers / label stocks hand back PNG. */
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 'P', 'N', 'G'};

    /**
     * The stored artifact as an image (GIF or PNG), for carriers that return
     * a raster label instead of ZPL/PDF. UPS's GIF was being stored and then
     * ignored — the sniffer only knew ZPL and PDF, so every surface fell
     * through to the facsimile while the real label sat in the row.
     */
    public Optional<ImageArtifact> resolveImage(Integer orderNo, Integer pkgIndex) {
        Optional<byte[]> gif = resolveAsBytes(orderNo, "GIF", pkgIndex);
        if (gif.isPresent()) return Optional.of(new ImageArtifact(gif.get(), "image/gif"));
        Optional<byte[]> png = resolveAsBytes(orderNo, "PNG", pkgIndex);
        return png.map(b -> new ImageArtifact(b, "image/png"));
    }

    public record ImageArtifact(byte[] bytes, String mimeType) {}

    private String sniffFormat(byte[] bytes) {
        int start = skipLeadingWhitespace(bytes);
        if (startsWithAt(bytes, MAGIC_ZPL, start)) return "ZPL";
        if (startsWithAt(bytes, MAGIC_PDF, start)) return "PDF";
        if (startsWithAt(bytes, MAGIC_GIF, start)) return "GIF";
        if (startsWithAt(bytes, MAGIC_PNG, start)) return "PNG";
        // BUG-001 I-001 sub-issue 1 (2026-10-20) — USPS writer prepends
        // a `^FX … ^FS` provenance comment before the `^XA` label-start
        // command, so the strict leading-magic check above misses real
        // USPS ZPLs (934392, 934393). PDF/GIF/PNG magic appears only at
        // byte 0 — they're already handled above — so a full-buffer scan
        // for `^XA` can only match ZPL semantics (even if a PDF content
        // stream happens to contain the three bytes, that PDF already
        // matched line 268 and returned). Keeps HEAD-probe cheap: scan
        // short-circuits on first hit.
        if (indexOf(bytes, MAGIC_ZPL) >= 0) return "ZPL";
        return null;
    }

    /** First-match offset of {@code needle} inside {@code haystack}, or -1. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle.length == 0) return -1;
        int limit = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * UPS's GraphicImage base64 decodes to bytes that begin with a
     * newline before {@code ^XA} (their formatter wraps output). PDFs
     * from other carriers occasionally have leading whitespace too.
     * Skip these so the magic-byte check finds the real start of the
     * payload instead of returning null and forcing a facsimile
     * fallback.
     */
    private int skipLeadingWhitespace(byte[] bytes) {
        if (bytes == null) return 0;
        int i = 0;
        while (i < bytes.length && (bytes[i] == '\n' || bytes[i] == '\r'
                || bytes[i] == ' ' || bytes[i] == '\t')) {
            i++;
        }
        return i;
    }

    private boolean startsWithAt(byte[] haystack, byte[] needle, int offset) {
        if (haystack == null || haystack.length - offset < needle.length) return false;
        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) return false;
        }
        return true;
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory defaultRequestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) FETCH_TIMEOUT.toMillis());
        f.setReadTimeout((int) FETCH_TIMEOUT.toMillis());
        return f;
    }
}
