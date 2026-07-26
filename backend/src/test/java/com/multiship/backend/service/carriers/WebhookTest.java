package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.TrackingWebhookEvent;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 36 — HMAC verification + payload parsing across four carriers.
 * One test file per the cross-carrier precedent.
 */
class WebhookTest {

    private static final String SECRET = "shared-secret-abc123";

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    /* -------------------------- Shared HMAC util -------------------------- */

    @Test
    void hmacSha256HexIsDeterministic() {
        String a = WebhookHmacUtil.hmacSha256Hex("hello", "secret");
        String b = WebhookHmacUtil.hmacSha256Hex("hello", "secret");
        assertEquals(a, b);
        assertNotNull(a);
        assertEquals(64, a.length(), "SHA-256 hex should be 64 characters");
    }

    @Test
    void hmacSha256HexDiffersByBody() {
        assertNotEquals(
                WebhookHmacUtil.hmacSha256Hex("payload-1", "secret"),
                WebhookHmacUtil.hmacSha256Hex("payload-2", "secret"));
    }

    @Test
    void hmacSha256HexReturnsNullForBlankSecret() {
        assertNull(WebhookHmacUtil.hmacSha256Hex("hello", ""));
        assertNull(WebhookHmacUtil.hmacSha256Hex("hello", null));
    }

    @Test
    void constantTimeEqualsHandlesCaseAndWhitespace() {
        assertTrue(WebhookHmacUtil.constantTimeEquals("ABC", "abc"));
        assertTrue(WebhookHmacUtil.constantTimeEquals(" abc ", "abc"));
        assertFalse(WebhookHmacUtil.constantTimeEquals("abc", null));
        assertFalse(WebhookHmacUtil.constantTimeEquals(null, "abc"));
    }

    /* -------------------------- UPS -------------------------- */

    @Test
    void upsSignatureVerificationAcceptsMatchingHmac() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String body = "{\"trackNumber\":\"1Z999\"}";
        String sig = WebhookHmacUtil.hmacSha256Hex(body, SECRET);
        assertTrue(c.verifyWebhookSignature(body, Map.of("X-UPS-Signature", sig), SECRET));
    }

    @Test
    void upsSignatureVerificationRejectsBadHmac() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        assertFalse(c.verifyWebhookSignature("{\"a\":1}",
                Map.of("X-UPS-Signature", "deadbeef"), SECRET));
    }

    @Test
    void upsSignatureVerificationRejectsMissingHeader() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        assertFalse(c.verifyWebhookSignature("{\"a\":1}", Map.of(), SECRET));
    }

    @Test
    void upsWebhookParsesDeliveredEvent() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String body = """
                {
                  "trackNumber": "1Z999AA10123456784",
                  "localActivityDate": "20260726",
                  "localActivityTime": "143000",
                  "activityLocation": {"city": "Louisville", "stateProvince": "KY", "country": "US"},
                  "activityStatus": {"code": "DL", "description": "Delivered", "type": "DL"}
                }""";
        TrackingWebhookEvent e = c.parseWebhookEvent(body, Map.of());
        assertNotNull(e);
        assertEquals("1Z999AA10123456784", e.trackingNumber());
        assertEquals("DL", e.eventType());
        assertEquals("DL", e.statusCode());
        assertTrue(e.delivered(), "code=DL means delivered");
        assertNotNull(e.location());
        assertNotNull(e.occurredAt());
    }

    @Test
    void upsWebhookMissingTrackNumberReturnsNull() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        assertNull(c.parseWebhookEvent("{}", Map.of()));
    }

    /* -------------------------- FedEx -------------------------- */

    @Test
    void fedexSignatureVerificationAcceptsMatchingHmac() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String body = "{\"trackingNumber\":\"794699999\"}";
        String sig = WebhookHmacUtil.hmacSha256Hex(body, SECRET);
        assertTrue(c.verifyWebhookSignature(body, Map.of("X-FedEx-Signature", sig), SECRET));
    }

    @Test
    void fedexWebhookParsesInTransitEvent() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String body = """
                {
                  "trackingNumber": "794699999999",
                  "shipmentStatusEventLocalTimeStamp": "2026-07-26T14:30:00-04:00",
                  "eventType": "IT",
                  "eventDescription": "In transit",
                  "eventLocation": {"city": "Memphis", "stateOrProvinceCode": "TN", "countryCode": "US"}
                }""";
        TrackingWebhookEvent e = c.parseWebhookEvent(body, Map.of());
        assertNotNull(e);
        assertEquals("794699999999", e.trackingNumber());
        assertEquals("IT", e.eventType());
        assertEquals("Memphis, TN US", e.location());
        assertFalse(e.delivered());
    }

    @Test
    void fedexWebhookDetectsDeliveredByEventTypeDL() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String body = """
                {
                  "trackingNumber": "794699999999",
                  "eventType": "DL",
                  "eventDescription": "Delivered"
                }""";
        TrackingWebhookEvent e = c.parseWebhookEvent(body, Map.of());
        assertTrue(e.delivered());
    }

    /* -------------------------- DHL -------------------------- */

    @Test
    void dhlSignatureVerificationAcceptsMatchingHmac() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String body = "{\"awb\":\"JD1234\"}";
        String sig = WebhookHmacUtil.hmacSha256Hex(body, SECRET);
        assertTrue(c.verifyWebhookSignature(body, Map.of("X-DHL-Signature", sig), SECRET));
    }

    @Test
    void dhlWebhookParsesDeliveredEvent() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String body = """
                {
                  "awb": "JD123456789",
                  "event": "Delivered",
                  "eventCode": "OK",
                  "eventTimestamp": "2026-07-26T14:30:00",
                  "location": {"cityName": "London", "countryCode": "GB"}
                }""";
        TrackingWebhookEvent e = c.parseWebhookEvent(body, Map.of());
        assertNotNull(e);
        assertEquals("JD123456789", e.trackingNumber());
        assertEquals("OK", e.eventType());
        assertTrue(e.delivered(), "eventCode=OK on DHL is delivered");
        assertEquals("London GB", e.location());
    }

    @Test
    void dhlWebhookMissingAwbReturnsNull() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        assertNull(c.parseWebhookEvent("{\"event\":\"In transit\"}", Map.of()));
    }

    /* -------------------------- SWSIM -------------------------- */

    @Test
    void stampsSignatureVerificationAcceptsMatchingHmac() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String body = "{\"TrackingNumber\":\"9400111\"}";
        String sig = WebhookHmacUtil.hmacSha256Hex(body, SECRET);
        assertTrue(c.verifyWebhookSignature(body, Map.of("X-Stamps-Signature", sig), SECRET));
    }

    @Test
    void stampsWebhookParsesDeliveredEvent() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String body = """
                {
                  "TrackingNumber": "9400111899223811234567",
                  "EventType": "Delivered",
                  "EventTimestamp": "2026-07-26T14:30:00",
                  "City": "Louisville",
                  "State": "KY",
                  "Country": "US"
                }""";
        TrackingWebhookEvent e = c.parseWebhookEvent(body, Map.of());
        assertNotNull(e);
        assertEquals("9400111899223811234567", e.trackingNumber());
        assertEquals("Delivered", e.eventType());
        assertTrue(e.delivered());
        assertNotNull(e.location());
        assertNotNull(e.occurredAt());
    }

    /* -------------------------- Cross-carrier record integrity -------------------------- */

    @Test
    void everyConnectorReturnsFalseForBlankSecret() {
        assertFalse(new UpsConnector(new CarrierProperties(), new ObjectMapper())
                .verifyWebhookSignature("{}", Map.of("X-UPS-Signature", "any"), ""));
        assertFalse(new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                .verifyWebhookSignature("{}", Map.of("X-FedEx-Signature", "any"), ""));
        assertFalse(new DhlConnector(new CarrierProperties(), new ObjectMapper())
                .verifyWebhookSignature("{}", Map.of("X-DHL-Signature", "any"), ""));
        assertFalse(new StampsConnector(new CarrierProperties(), new ObjectMapper())
                .verifyWebhookSignature("{}", Map.of("X-Stamps-Signature", "any"), ""));
    }

    private static void assertNotEquals(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
