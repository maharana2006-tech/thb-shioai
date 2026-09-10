package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CarrierRateLimit}. Covers the RFC 7231 §7.1.3
 * two-format Retry-After parsing (integer-seconds + HTTP-date) plus
 * the connector-facing convenience helpers.
 */
class CarrierRateLimitTest {

    /* -------- parseRetryAfterValue: raw-string parsing -------- */

    @Test
    void parsesIntegerSecondsFormat() {
        assertEquals(Optional.of(Duration.ofSeconds(30)),
                CarrierRateLimit.parseRetryAfterValue("30"));
        assertEquals(Optional.of(Duration.ofSeconds(0)),
                CarrierRateLimit.parseRetryAfterValue("0"));
        assertEquals(Optional.of(Duration.ofSeconds(3600)),
                CarrierRateLimit.parseRetryAfterValue("3600"));
    }

    @Test
    void integerSecondsToleratesLeadingTrailingWhitespace() {
        assertEquals(Optional.of(Duration.ofSeconds(15)),
                CarrierRateLimit.parseRetryAfterValue("  15  "));
    }

    @Test
    void negativeIntegerRejected() {
        assertEquals(Optional.empty(),
                CarrierRateLimit.parseRetryAfterValue("-1"));
    }

    @Test
    void nonNumericNonDateRejected() {
        assertEquals(Optional.empty(),
                CarrierRateLimit.parseRetryAfterValue("later"));
        assertEquals(Optional.empty(),
                CarrierRateLimit.parseRetryAfterValue(""));
        assertEquals(Optional.empty(),
                CarrierRateLimit.parseRetryAfterValue(null));
    }

    @Test
    void parsesHttpDateFormatInFuture() {
        // Build an HTTP-date 60 seconds from now — the parser subtracts
        // 'now' to produce a positive Duration.
        String future = OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(60)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        Duration parsed = CarrierRateLimit.parseRetryAfterValue(future).orElseThrow();
        // Allow ±5s slack for test-execution jitter.
        assertTrue(parsed.toSeconds() >= 55 && parsed.toSeconds() <= 65,
                "Expected ~60s, got " + parsed.toSeconds());
    }

    @Test
    void httpDateInPastReturnsZeroDuration() {
        String past = OffsetDateTime.now(ZoneOffset.UTC)
                .minusSeconds(300)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(Duration.ZERO,
                CarrierRateLimit.parseRetryAfterValue(past).orElseThrow(),
                "A Retry-After date in the past should yield zero delay (retry immediately).");
    }

    /* -------- parseRetryAfter: from exception -------- */

    @Test
    void parseRetryAfterFromException() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "45");
        RestClientResponseException ex = new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                headers, new byte[0], StandardCharsets.UTF_8);
        assertEquals(Optional.of(Duration.ofSeconds(45)),
                CarrierRateLimit.parseRetryAfter(ex));
    }

    @Test
    void parseRetryAfterReturnsEmptyWhenHeaderAbsent() {
        RestClientResponseException ex = new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), CarrierRateLimit.parseRetryAfter(ex));
    }

    @Test
    void parseRetryAfterOrDefaultFallsBackToDefault() {
        RestClientResponseException ex = new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertEquals(CarrierRateLimit.DEFAULT_BACKOFF,
                CarrierRateLimit.parseRetryAfterOrDefault(ex));
    }

    /* -------- isRateLimited -------- */

    @Test
    void isRateLimitedTrueOnly429() {
        RestClientResponseException tooMany = new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertTrue(CarrierRateLimit.isRateLimited(tooMany));

        RestClientResponseException notFound = new HttpClientErrorException(
                HttpStatus.NOT_FOUND, "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertFalse(CarrierRateLimit.isRateLimited(notFound));

        assertFalse(CarrierRateLimit.isRateLimited(null));
    }

    /* -------- describe -------- */

    @Test
    void describeIncludesRetryAfterValueWhenPresent() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "45");
        RestClientResponseException ex = new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "", headers, new byte[0], StandardCharsets.UTF_8);
        String description = CarrierRateLimit.describe(ex);
        assertTrue(description.contains("45s"),
                "describe() must surface the parsed Retry-After for operator diagnosis. Got: " + description);
        assertTrue(description.contains("rate limited"),
                "describe() must state the situation plainly. Got: " + description);
    }

    @Test
    void describeFallsBackWhenHeaderAbsent() {
        RestClientResponseException ex = new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        String description = CarrierRateLimit.describe(ex);
        assertTrue(description.contains("absent"),
                "describe() must call out that the header was missing when it is. Got: " + description);
        assertTrue(description.contains(String.valueOf(CarrierRateLimit.DEFAULT_BACKOFF.toSeconds())),
                "describe() must include the default backoff when falling back. Got: " + description);
    }
}
