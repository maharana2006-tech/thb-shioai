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

        byte[] bytes = fetchBytes(stored);
        if (bytes == null || bytes.length == 0) return Optional.empty();

        String detected = sniffFormat(bytes);
        if (detected == null) return Optional.empty();
        if (!detected.equals(normalized)) return Optional.empty();

        return Optional.of(bytes);
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
        return null;
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
