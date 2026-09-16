package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link UspsDirectConnector#verifyWebhookSignature}.
 *
 * <p>USPS Subscriptions-Tracking v3.2 signs each push with
 * {@code X-HMAC = base64(HMAC-SHA256(timestamp + rawBody, secret))}.
 * The timestamp is carried either in the {@code X-USPS-Timestamp}
 * header (preferred) or in the payload's {@code eventTimestamp} field
 * as a fallback — real fixtures cover both paths.
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Valid signature → true.</li>
 *   <li>Wrong secret / tampered payload / missing header / missing
 *       secret → false, never throw.</li>
 *   <li>Constant-time comparison via {@link java.security.MessageDigest#isEqual}
 *       — verified by inspecting the source; timing itself isn't
 *       assertable in a unit test.</li>
 * </ul>
 */
class UspsDirectWebhookHmacTest {

    private static final String SECRET = "shared-usps-webhook-secret-abc123";
    private static final String TIMESTAMP = "2026-09-15T13:45:00Z";

    private UspsDirectConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        ObjectMapper om = new ObjectMapper();
        connector = new UspsDirectConnector(
                props, om,
                new UspsOAuthTokenCache(om),
                new UspsPaymentAuthCache(om),
                mock(JdbcTemplate.class));
    }

    /** Reference HMAC — computed inline so this test doesn't rely on
     *  the connector's own {@code hmacSha256Base64} to verify itself. */
    private static String hmacBase64(String message, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static Map<String, String> signedHeaders(String rawPayload, String secret,
                                                     String timestamp) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-HMAC", hmacBase64(timestamp + rawPayload, secret));
        h.put("X-USPS-Timestamp", timestamp);
        return h;
    }

    // ================================================================
    // Valid signature
    // ================================================================

    @Test
    void validSignatureVerifiesTrue() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        Map<String, String> headers = signedHeaders(rawPayload, SECRET, TIMESTAMP);

        assertTrue(connector.verifyWebhookSignature(rawPayload, headers, SECRET),
                "matching HMAC + timestamp + secret must verify true");
    }

    @Test
    void validSignatureVerifiesForInTransitFixtureToo() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_in_transit.json");
        Map<String, String> headers = signedHeaders(rawPayload, SECRET, TIMESTAMP);

        assertTrue(connector.verifyWebhookSignature(rawPayload, headers, SECRET));
    }

    /** USPS docs are inconsistent about whether X-USPS-Timestamp is
     *  required or optional. When absent, the payload's own
     *  {@code eventTimestamp} field serves as the second HMAC input —
     *  same replay-protection guarantee, one input less to forget. */
    @Test
    void missingTimestampHeaderFallsBackToPayloadEventTimestamp() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        // The delivered fixture's eventTimestamp is "2026-09-15T16:35:00Z".
        String eventTs = "2026-09-15T16:35:00Z";
        Map<String, String> headers = new HashMap<>();
        headers.put("X-HMAC", hmacBase64(eventTs + rawPayload, SECRET));
        // No X-USPS-Timestamp — connector must fall back to eventTimestamp.

        assertTrue(connector.verifyWebhookSignature(rawPayload, headers, SECRET),
                "missing X-USPS-Timestamp header should fall back to payload eventTimestamp");
    }

    // ================================================================
    // Rejections — every failure mode returns false, never throws
    // ================================================================

    @Test
    void wrongSecretVerifiesFalse() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        Map<String, String> headers = signedHeaders(rawPayload, SECRET, TIMESTAMP);

        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, "wrong-secret"),
                "signature signed with SECRET must not verify against wrong-secret");
    }

    @Test
    void tamperedPayloadVerifiesFalse() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        Map<String, String> headers = signedHeaders(rawPayload, SECRET, TIMESTAMP);

        // Flip a byte — signature no longer matches the raw body.
        String tampered = rawPayload.replace("DELIVERED", "TAMPERED");
        assertFalse(connector.verifyWebhookSignature(tampered, headers, SECRET),
                "tampered raw payload must not verify against original signature");
    }

    @Test
    void tamperedTimestampVerifiesFalse() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        Map<String, String> headers = signedHeaders(rawPayload, SECRET, TIMESTAMP);
        // Move the timestamp forward by a day — HMAC input differs.
        headers.put("X-USPS-Timestamp", "2026-09-16T13:45:00Z");

        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, SECRET),
                "replaying with an altered timestamp must not verify");
    }

    @Test
    void missingHmacHeaderVerifiesFalseNoThrow() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        Map<String, String> headers = new HashMap<>();
        headers.put("X-USPS-Timestamp", TIMESTAMP);
        // No X-HMAC — must fail closed without an NPE.

        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, SECRET),
                "missing X-HMAC header must verify false without throwing");
    }

    @Test
    void emptyHmacHeaderVerifiesFalseNoThrow() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        Map<String, String> headers = new HashMap<>();
        headers.put("X-HMAC", "");
        headers.put("X-USPS-Timestamp", TIMESTAMP);

        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, SECRET),
                "empty X-HMAC header must verify false");
    }

    @Test
    void missingSecretVerifiesFalseNoThrow() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        Map<String, String> headers = signedHeaders(rawPayload, SECRET, TIMESTAMP);

        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, null),
                "null secret must verify false");
        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, ""),
                "empty secret must verify false");
        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, "   "),
                "whitespace-only secret must verify false");
    }

    @Test
    void missingRawPayloadVerifiesFalseNoThrow() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-HMAC", "any-value");
        headers.put("X-USPS-Timestamp", TIMESTAMP);
        assertFalse(connector.verifyWebhookSignature(null, headers, SECRET),
                "null rawPayload must verify false");
    }

    @Test
    void missingHeadersMapVerifiesFalseNoThrow() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        assertFalse(connector.verifyWebhookSignature(rawPayload, null, SECRET),
                "null headers map must verify false");
        assertFalse(connector.verifyWebhookSignature(rawPayload, new HashMap<>(), SECRET),
                "empty headers map must verify false");
    }

    @Test
    void missingTimestampFromBothPlacesVerifiesFalse() {
        String rawPayload = "{\"trackingNumber\":\"9400\"}";  // no eventTimestamp field
        Map<String, String> headers = new HashMap<>();
        headers.put("X-HMAC", "any-value");
        // No X-USPS-Timestamp header AND no eventTimestamp in body.
        assertFalse(connector.verifyWebhookSignature(rawPayload, headers, SECRET),
                "missing timestamp in both header AND payload must verify false");
    }

    // ================================================================
    // Header lookup is case-insensitive (matches HTTP semantics)
    // ================================================================

    @Test
    void headerLookupIsCaseInsensitive() throws Exception {
        String rawPayload = loadFixture("usps/v3/webhooks/webhook_valid_delivered.json");
        String hmac = hmacBase64(TIMESTAMP + rawPayload, SECRET);
        Map<String, String> headers = new HashMap<>();
        // Lowercase variants — must still be found by the connector.
        headers.put("x-hmac", hmac);
        headers.put("x-usps-timestamp", TIMESTAMP);
        assertTrue(connector.verifyWebhookSignature(rawPayload, headers, SECRET),
                "header names are case-insensitive per HTTP spec");
    }

    // ================================================================
    // hmacSha256Base64 helper — pin the exact digest shape
    // ================================================================

    @Test
    void hmacSha256Base64Deterministic() {
        String a = UspsDirectConnector.hmacSha256Base64("hello", "secret");
        String b = UspsDirectConnector.hmacSha256Base64("hello", "secret");
        assertEquals(a, b);
        assertNotNull(a);
        // Base64-encoded SHA-256 = 44 chars (32 bytes * 4/3, padded).
        assertEquals(44, a.length(), "base64(SHA-256) should be 44 characters incl. padding");
    }

    @Test
    void hmacSha256Base64ReturnsNullForBlankInput() {
        assertEquals(null, UspsDirectConnector.hmacSha256Base64("hello", ""));
        assertEquals(null, UspsDirectConnector.hmacSha256Base64("hello", null));
        assertEquals(null, UspsDirectConnector.hmacSha256Base64(null, "secret"));
    }

    // ================================================================
    // extractPayloadTimestamp — best-effort scanner
    // ================================================================

    @Test
    void extractPayloadTimestampFindsField() {
        String payload = "{\"trackingNumber\":\"9400\",\"eventTimestamp\":\"2026-09-15T13:00:00Z\"}";
        assertEquals("2026-09-15T13:00:00Z",
                UspsDirectConnector.extractPayloadTimestamp(payload));
    }

    @Test
    void extractPayloadTimestampReturnsNullWhenAbsent() {
        assertEquals(null,
                UspsDirectConnector.extractPayloadTimestamp("{\"trackingNumber\":\"9400\"}"));
        assertEquals(null, UspsDirectConnector.extractPayloadTimestamp(null));
        assertEquals(null, UspsDirectConnector.extractPayloadTimestamp(""));
    }

    // ================================================================
    // Fixture helper
    // ================================================================

    private static String loadFixture(String resourcePath) throws IOException {
        try (java.io.InputStream in = Objects.requireNonNull(
                UspsDirectWebhookHmacTest.class.getClassLoader().getResourceAsStream(resourcePath),
                "fixture not on classpath: " + resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
