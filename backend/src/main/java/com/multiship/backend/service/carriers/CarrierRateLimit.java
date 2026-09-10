package com.multiship.backend.service.carriers;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Parses the {@code Retry-After} header returned by carrier APIs on
 * HTTP 429 responses. FedEx/UPS/DHL/USPS all set this on rate-limit
 * rejections; without a common parser each connector would need to
 * duplicate the logic and none actually do — 429 was silently mapped
 * to a generic "carrier error" and the operator never saw the delay.
 *
 * <p>Per RFC 7231 §7.1.3 the header value is either:
 * <ul>
 *   <li>a positive integer number of seconds (e.g. {@code Retry-After: 30}), OR</li>
 *   <li>an HTTP-date (e.g. {@code Retry-After: Wed, 21 Oct 2015 07:28:00 GMT}).</li>
 * </ul>
 *
 * <p>This utility returns the delay as a {@link Duration} regardless of
 * which format the carrier used, so the caller can log it or (in a
 * future PR) feed it into a backoff scheduler.
 *
 * <p>Currently used only for logging in connector error handlers; the
 * actual retry-with-backoff will come with a bounded-jitter scheduler
 * in BulkLabelServiceImpl. Establishing this utility now makes that
 * follow-up small.
 */
public final class CarrierRateLimit {

    /** Fallback delay when the header is missing or unparseable but the
     *  status code is 429. Long enough to matter, short enough not to
     *  strand a bulk batch behind a single 429. */
    public static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(15);

    private CarrierRateLimit() {}

    /**
     * True when the exception represents an HTTP 429 (Too Many Requests).
     * Convenience wrapper so callers don't have to import HttpStatus in
     * their error handlers.
     */
    public static boolean isRateLimited(RestClientResponseException ex) {
        return ex != null && ex.getStatusCode().value() == 429;
    }

    /**
     * Extracts the {@code Retry-After} header from a carrier response,
     * parsing either the seconds-integer or HTTP-date format. Returns
     * empty when the header is absent OR the value can't be parsed.
     */
    public static Optional<Duration> parseRetryAfter(RestClientResponseException ex) {
        if (ex == null) return Optional.empty();
        HttpHeaders headers = ex.getResponseHeaders();
        if (headers == null) return Optional.empty();
        List<String> values = headers.get(HttpHeaders.RETRY_AFTER);
        if (values == null || values.isEmpty()) return Optional.empty();
        return parseRetryAfterValue(values.get(0));
    }

    /**
     * Same as {@link #parseRetryAfter(RestClientResponseException)} but
     * returns {@link #DEFAULT_BACKOFF} when the header is missing or
     * unparseable. Use this when you need a definite Duration to log
     * or schedule against, and a conservative default is acceptable.
     */
    public static Duration parseRetryAfterOrDefault(RestClientResponseException ex) {
        return parseRetryAfter(ex).orElse(DEFAULT_BACKOFF);
    }

    /**
     * Package-private for direct-string tests. Parses either the
     * integer-seconds form or the HTTP-date form per RFC 7231 §7.1.3.
     * Never throws — returns empty on malformed input so a broken
     * header doesn't break the calling error handler.
     */
    static Optional<Duration> parseRetryAfterValue(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        // Try integer-seconds first — cheap and by far the more common
        // form on carrier APIs (UPS/FedEx/DHL/USPS all use it).
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) return Optional.empty();
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException notInteger) {
            // Fall through to HTTP-date parsing.
        }
        // HTTP-date form: parse then compute the delta from now.
        try {
            OffsetDateTime target = OffsetDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            long deltaSeconds = target.toEpochSecond() - Instant.now().getEpochSecond();
            if (deltaSeconds < 0) return Optional.of(Duration.ZERO);
            return Optional.of(Duration.ofSeconds(deltaSeconds));
        } catch (DateTimeParseException notADate) {
            return Optional.empty();
        }
    }

    /**
     * Human-readable summary of a 429 for log lines — e.g.
     * "rate limited (Retry-After: 30s)" or "rate limited (Retry-After header absent, defaulting to 15s)".
     * Keep short so it fits in a single log line alongside the carrier
     * name and endpoint.
     */
    public static String describe(RestClientResponseException ex) {
        Optional<Duration> parsed = parseRetryAfter(ex);
        if (parsed.isPresent()) {
            return "rate limited (Retry-After: " + parsed.get().toSeconds() + "s)";
        }
        return "rate limited (Retry-After header absent, defaulting to " + DEFAULT_BACKOFF.toSeconds() + "s)";
    }
}
