package com.multiship.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * PR #550 (Sprint 52 follow-up) — turns a carrier-returned
 * {@code label_file_path} candidate into a form that survives
 * indefinitely in the database.
 *
 * <p>Some carriers (FedEx MPS piece labels in particular) return SIGNED
 * URLs pointing to the actual label bytes. Those URLs are typically valid
 * for only 24-48 hours; storing them verbatim means any label lookup
 * after the URL expires returns nothing and the FE has to fall back to
 * the JSX facsimile. Order 900016 hit exactly this class of failure —
 * see {@code project_label_preview_audit.md} STATE_3.
 *
 * <p>This service pre-fetches URLs at persistence time and stores the
 * base64-encoded label bytes instead. Best-effort — if the fetch fails
 * for any reason (network, 4xx, size cap), we fall back to storing the
 * URL unchanged. That's no worse than pre-#550 behaviour; the shipment
 * label is still generated + persisted, the URL-expiry failure just
 * degrades to the pre-#550 shape rather than blocking generation.
 *
 * <p>Detection rules mirror {@link LabelArtifactResolver}:
 * <ul>
 *   <li>null / blank → returned as-is</li>
 *   <li>Starts with {@code ^XA} → raw ZPL, returned as-is</li>
 *   <li>Starts with {@code http://} or {@code https://} → fetch + base64-encode</li>
 *   <li>Otherwise → assume base64 already, returned as-is</li>
 * </ul>
 */
@Slf4j
@Service
public class LabelBytesPersister {

    /** Short read timeout on URL fetches. Carrier signed URLs typically
     *  respond in &lt;500ms right after label generation; anything longer
     *  is a red flag and we fall back to storing the URL. */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    /** Hard cap on stored label size. Base64 grows by ~33%, so a 1 MB
     *  raw label becomes ~1.4 MB TEXT. Larger than that is either a
     *  misconfigured carrier response or something weird — either way
     *  we don't want to bloat the row. Falls back to storing the URL
     *  when exceeded. */
    private static final int MAX_BYTES_DEFAULT = 1_000_000;

    private final int maxBytes;
    private final RestClient http;

    public LabelBytesPersister(
            @Value("${label.persister.max-bytes:1000000}") int maxBytesOverride) {
        this.maxBytes = maxBytesOverride > 0 ? maxBytesOverride : MAX_BYTES_DEFAULT;
        this.http = RestClient.builder()
                .requestFactory(defaultRequestFactory())
                .build();
    }

    /**
     * Turn a raw carrier-returned label value (URL, raw ZPL, or base64)
     * into what should go into {@code label_file_path}.
     *
     * @return the input verbatim when passthrough is safe; base64-encoded
     *         bytes when the input was a URL we successfully fetched;
     *         the original URL when a fetch was attempted but failed
     *         (best-effort). Never returns {@code null} unless input was
     *         {@code null} / blank.
     */
    public String toPersistable(String candidate) {
        if (!StringUtils.hasText(candidate)) return candidate;
        String trimmed = candidate.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        // Raw ZPL passthrough — the format resolver serves this verbatim.
        if (trimmed.startsWith("^XA")) return candidate;
        // URL branch — fetch + base64 encode. Fallback: return the URL
        // unchanged so we don't block persistence of an otherwise-valid
        // shipment on a transient network problem.
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            byte[] bytes = fetchBytesCapped(trimmed);
            if (bytes == null || bytes.length == 0) {
                log.debug("LabelBytesPersister: fetch returned no bytes for {}, storing URL", trimmed);
                return candidate;
            }
            return Base64.getEncoder().encodeToString(bytes);
        }
        // Not a URL and doesn't start with ^XA — assume already base64
        // (FedEx encodedLabel, UPS GraphicImage, DHL labelImages, Stamps
        // ImageData). Passthrough.
        return candidate;
    }

    /**
     * Fetch the URL with the configured timeout + size cap. Returns
     * {@code null} on any failure so callers can fall back to storing the
     * URL. Never throws.
     */
    private byte[] fetchBytesCapped(String url) {
        try {
            byte[] body = http.get().uri(url).retrieve().body(byte[].class);
            if (body == null || body.length == 0) return null;
            if (body.length > maxBytes) {
                log.warn("LabelBytesPersister: {} returned {} bytes (cap={}); storing URL instead",
                        url, body.length, maxBytes);
                return null;
            }
            return body;
        } catch (Exception ex) {
            log.debug("LabelBytesPersister: URL fetch failed for {}: {}", url, ex.getMessage());
            return null;
        }
    }

    private static SimpleClientHttpRequestFactory defaultRequestFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) FETCH_TIMEOUT.toMillis());
        f.setReadTimeout((int) FETCH_TIMEOUT.toMillis());
        return f;
    }
}
