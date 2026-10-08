package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.PickupRequest;
import com.multiship.backend.service.carriers.CarrierConnector.PickupResult;
import com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-T5 — SERA pickup wire tests ({@code POST /sera/v1/pickups} +
 * {@code DELETE /sera/v1/pickups/{pickup_id}}). Coverage:
 * <ol>
 *   <li>Happy POST — {@code pickup_id} lands on {@link PickupResult#confirmationNumber()}.</li>
 *   <li>Happy DELETE — 2xx surfaces as {@code CANCELLED}.</li>
 *   <li>800103 Sunday — caught client-side, no round-trip (IllegalArgumentException).</li>
 *   <li>800106 address mismatch — server-propagated as ERROR with the enum classification.</li>
 *   <li>800101 multi-carrier — server-propagated as ERROR with the enum classification.</li>
 *   <li>404 on DELETE — surfaces as ALREADY_CANCELLED (idempotent cancel).</li>
 *   <li>{@link IdempotencyKeys#forStampsPickup} is deterministic + canonical UUID.</li>
 * </ol>
 *
 * <p>Transport is stubbed via a package-private {@link StubConnector}
 * subclass — same seam pattern Agent 1 established for the USPS address
 * validation tests. No MockWebServer, no real HTTP.
 */
class StampsSeraPickupTest {

    private static final String TOKEN = "real-sera-token";
    private ObjectMapper objectMapper;
    private StubConnector connector;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        s.setSeraApiBaseUrl("https://api.stampsendicia.com/sera/v1");
        s.setSeraSandboxApiBaseUrl("https://api.testing.stampsendicia.com/sera/v1");
        connector = new StubConnector(props, objectMapper);
    }

    // ===== 1. Happy POST =====

    @Test
    void schedulePickupSera_happyPath_persistsPickupId() {
        String pickupUuid = UUID.randomUUID().toString();
        connector.nextPostResponse = "{\"pickup_id\":\"" + pickupUuid + "\","
                + "\"carrier\":\"usps\",\"number_of_items_in_pickup\":2,"
                + "\"estimated_cost\":0.00,\"pickup_window\":{\"start_at\":\"2026-10-09T09:00:00Z\","
                + "\"end_at\":\"2026-10-09T17:00:00Z\"}}";
        PickupRequest req = baseRequest(nextBizDayFromToday());
        PickupResult r = connector.schedulePickupSera(req, TOKEN, "PRODUCTION");
        assertEquals("SCHEDULED", r.status());
        assertEquals(pickupUuid, r.confirmationNumber());
        assertEquals("USPS", r.carrierCode());
        assertTrue(r.message().contains(pickupUuid),
                "message must surface the pickup_id to the operator; got: " + r.message());
    }

    // ===== 2. Happy DELETE =====

    @Test
    void cancelPickupSera_happyPath_surfacesCancelled() {
        String pickupUuid = UUID.randomUUID().toString();
        connector.nextDeleteResponse = ResponseEntity.ok("{\"status\":\"cancelled\"}");
        PickupResult r = connector.cancelPickupSera(pickupUuid, TOKEN, "PRODUCTION");
        assertEquals("CANCELLED", r.status());
        assertEquals(pickupUuid, r.confirmationNumber());
    }

    @Test
    void cancelPickupSera_204NoContent_surfacesCancelled() {
        String pickupUuid = UUID.randomUUID().toString();
        connector.nextDeleteResponse = ResponseEntity.noContent().build();
        PickupResult r = connector.cancelPickupSera(pickupUuid, TOKEN, "PRODUCTION");
        assertEquals("CANCELLED", r.status());
    }

    // ===== 3. 800103 Sunday — client-side guard =====

    @Test
    void schedulePickupSera_sundayPickup_throwsLocally_noRoundTrip() {
        LocalDate sunday = findNextSunday();
        PickupRequest req = baseRequest(sunday);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.schedulePickupSera(req, TOKEN, "PRODUCTION"));
        assertTrue(ex.getMessage().toLowerCase().contains("sunday"),
                "message must name Sunday; got: " + ex.getMessage());
        assertEquals(0, connector.postCallCount,
                "Sunday guard must fail before any HTTP round-trip");
    }

    // ===== 4. 800106 address mismatch — server propagation =====

    @Test
    void schedulePickupSera_800106AddressMismatch_propagatedFromServer() {
        String body = "{\"error_code\":\"800106\",\"error_message\":"
                + "\"Labels must have matching address and contact.\"}";
        connector.nextPostException = httpErr(HttpStatus.BAD_REQUEST, body);
        PickupRequest req = baseRequest(nextBizDayFromToday());
        PickupResult r = connector.schedulePickupSera(req, TOKEN, "PRODUCTION");
        assertEquals("ERROR", r.status());
        assertNull(r.confirmationNumber());
        assertTrue(r.message().contains("800106"),
                "message must surface the raw SERA code; got: " + r.message());
        assertTrue(r.message().contains("PICKUP_ADDRESS_MISMATCH"),
                "message must surface the enum classification; got: " + r.message());
    }

    // ===== 5. 800101 multi-carrier — server propagation =====

    @Test
    void schedulePickupSera_800101MultiCarrier_propagatedFromServer() {
        String body = "{\"error_code\":\"800101\",\"error_message\":"
                + "\"Labels belong to multiple carriers.\"}";
        connector.nextPostException = httpErr(HttpStatus.BAD_REQUEST, body);
        PickupRequest req = baseRequest(nextBizDayFromToday());
        PickupResult r = connector.schedulePickupSera(req, TOKEN, "PRODUCTION");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("800101"));
        assertTrue(r.message().contains("PICKUP_MULTI_CARRIER"));
    }

    // ===== 6. 404 on DELETE — ALREADY_CANCELLED =====

    @Test
    void cancelPickupSera_404_treatedAsAlreadyCancelled() {
        String pickupUuid = UUID.randomUUID().toString();
        connector.nextDeleteException = httpErr(HttpStatus.NOT_FOUND,
                "{\"error_code\":\"800010\",\"error_message\":\"pickup not found\"}");
        PickupResult r = connector.cancelPickupSera(pickupUuid, TOKEN, "PRODUCTION");
        assertEquals("ALREADY_CANCELLED", r.status(),
                "a 404 on cancel is idempotent-success semantics, not an error");
        assertEquals(pickupUuid, r.confirmationNumber());
        assertTrue(r.message().toLowerCase().contains("already"),
                "message must flag the already-cancelled branch; got: " + r.message());
    }

    @Test
    void cancelPickupSera_500_propagatedAsError() {
        connector.nextDeleteException = httpErr(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"error_code\":\"899999\",\"error_message\":\"server exploded\"}");
        PickupResult r = connector.cancelPickupSera("some-uuid", TOKEN, "PRODUCTION");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("500"));
    }

    // ===== 7. IdempotencyKeys.forStampsPickup — determinism + canonical UUID =====

    @Test
    void forStampsPickup_isDeterministic() {
        LocalDate d = LocalDate.of(2026, 10, 9);
        String a = IdempotencyKeys.forStampsPickup("acct-123", d);
        String b = IdempotencyKeys.forStampsPickup("acct-123", d);
        assertEquals(a, b, "same (scope, date) must produce the same key");
    }

    @Test
    void forStampsPickup_differentScope_producesDifferentKey() {
        LocalDate d = LocalDate.of(2026, 10, 9);
        String a = IdempotencyKeys.forStampsPickup("acct-123", d);
        String b = IdempotencyKeys.forStampsPickup("acct-456", d);
        assertFalse(a.equals(b), "different scope must produce different keys");
    }

    @Test
    void forStampsPickup_differentDate_producesDifferentKey() {
        String a = IdempotencyKeys.forStampsPickup("acct-123", LocalDate.of(2026, 10, 9));
        String b = IdempotencyKeys.forStampsPickup("acct-123", LocalDate.of(2026, 10, 10));
        assertFalse(a.equals(b), "different pickup date must produce different keys");
    }

    @Test
    void forStampsPickup_producesCanonicalUuid() {
        String key = IdempotencyKeys.forStampsPickup("acct-123", LocalDate.of(2026, 10, 9));
        // Canonical UUID: 8-4-4-4-12 hex (SERA documents this shape on the wire).
        UUID parsed = UUID.fromString(key);
        assertNotNull(parsed);
        assertEquals(key, parsed.toString(), "key must round-trip through UUID.fromString → .toString");
    }

    @Test
    void forStampsPickup_rejectsBlankScope() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsPickup("  ", LocalDate.now()));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsPickup(null, LocalDate.now()));
    }

    @Test
    void forStampsPickup_rejectsNullDate() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsPickup("acct", null));
    }

    // ===== bonus: body shape smoke-check =====

    @Test
    void buildSeraPickupBody_emitsRequiredFields() throws Exception {
        PickupRequest req = baseRequest(LocalDate.of(2026, 10, 9));
        String json = connector.buildSeraPickupBody(req);
        JsonNode root = objectMapper.readTree(json);
        assertEquals("usps", root.path("carrier").asText());
        assertTrue(root.has("pickup_address"), "must emit pickup_address");
        assertEquals("1 Warehouse Way",
                root.path("pickup_address").path("address_line1").asText());
        assertTrue(root.has("pickup_window"), "must emit pickup_window");
        assertTrue(root.path("pickup_window").path("start_at").asText().startsWith("2026-10-09T"),
                "start_at must be ISO-8601 anchored on pickupDate");
        assertTrue(root.path("pickup_window").path("end_at").asText().startsWith("2026-10-09T"),
                "end_at must be ISO-8601 anchored on pickupDate");
    }

    // ===== helpers =====

    /** Compute the next legal pickup date (next business day from "today",
     *  per the connector's own guard). Keeps the Sunday/weekday rotation
     *  test-stable regardless of when CI runs. */
    private static LocalDate nextBizDayFromToday() {
        LocalDate d = LocalDate.now().plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY
                || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Find a Sunday that falls on-or-after today + 1 so the test is
     *  independent of the current day of week. */
    private static LocalDate findNextSunday() {
        LocalDate d = LocalDate.now().plusDays(1);
        while (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private static PickupRequest baseRequest(LocalDate pickupDate) {
        AddressToValidate addr = new AddressToValidate(
                "Acme Warehouse", "Acme Co.",
                "1 Warehouse Way", null, null,
                "Louisville", "KY", "40209", "US");
        return new PickupRequest(
                pickupDate,
                LocalTime.of(10, 0),
                LocalTime.of(16, 0),
                addr,
                "Shipping Dept.",
                "5551234567",
                2,
                new BigDecimal("5.0"),
                "LB",
                "Leave at loading dock",
                "123456789",
                null, null, null, null, null);
    }

    private static org.springframework.web.client.RestClientResponseException httpErr(
            HttpStatus status, String body) {
        if (status.is5xxServerError()) {
            return HttpServerErrorException.create(
                    status, status.getReasonPhrase(),
                    new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
        return HttpClientErrorException.create(
                status, status.getReasonPhrase(),
                new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    /** Stub subclass — overrides the two seam methods so we can inject
     *  canned responses + exceptions without a MockWebServer. Also pins
     *  "today" so the next-business-day guard is dynamic and test-stable
     *  regardless of calendar date. */
    static class StubConnector extends StampsConnector {
        String nextPostResponse;
        org.springframework.web.client.RestClientResponseException nextPostException;
        ResponseEntity<String> nextDeleteResponse;
        org.springframework.web.client.RestClientResponseException nextDeleteException;
        int postCallCount = 0;
        int deleteCallCount = 0;

        StubConnector(CarrierProperties props, ObjectMapper mapper) {
            super(props, mapper);
        }

        @Override
        String executeSeraPickupPost(String url, String body, String accessToken, String idempotencyKey) {
            postCallCount++;
            if (nextPostException != null) throw nextPostException;
            return nextPostResponse;
        }

        @Override
        ResponseEntity<String> executeSeraPickupDelete(String url, String accessToken) {
            deleteCallCount++;
            if (nextDeleteException != null) throw nextDeleteException;
            return nextDeleteResponse;
        }
    }
}
