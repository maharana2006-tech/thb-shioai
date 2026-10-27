package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.PickupRequest;
import com.multiship.backend.service.carriers.CarrierConnector.PickupResult;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-022 — a carrier's pickup rejection must reach the operator as the
 * carrier's own reason (and code), the way SERA/USPS already did, not as a
 * bare "HTTP 401" / "HTTP 400". The HTTP layer is stubbed through the
 * global interceptor so the real catch block in each connector runs.
 */
class PickupCarrierErrorTest {

    private static final String UPS_401 =
            "{\"response\":{\"errors\":[{\"code\":\"250002\",\"message\":\"Invalid Authentication Information.\"}]}}";
    private static final String FEDEX_400 =
            "{\"transactionId\":\"e7eeb736\",\"errors\":[{\"code\":\"GROUND.SERVICES.UNAVAILABLE\","
            + "\"message\":\"There are no valid FedEx Ground services available. Please update and try again.\"}]}";

    @AfterEach
    void unstub() {
        HttpClients.setGlobalInterceptor(null);
    }

    private static void respondWith(HttpStatus status, String body) {
        HttpClients.setGlobalInterceptor((request, bytes, execution) -> {
            MockClientHttpResponse r = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
            r.getHeaders().add("Content-Type", "application/json");
            return r;
        });
    }

    private static PickupRequest request() {
        return new PickupRequest(LocalDate.of(2026, 10, 29), LocalTime.of(13, 0), LocalTime.of(17, 0),
                new AddressToValidate("QA Pickup", null, "100 Test Way", null, null, "New York", "NY", "10118", "US"),
                "QA Pickup", "2125551212", 1, new BigDecimal("5"), "LB", "QA sandbox pickup - ignore",
                "740561111", "GROUND", null, null, null, null);
    }

    @Test
    void ups_401_surfaces_code_and_reason() {
        respondWith(HttpStatus.UNAUTHORIZED, UPS_401);
        PickupResult r = new UpsConnector(new CarrierProperties(), new ObjectMapper())
                .schedulePickup(request(), "real-ups-token", "SANDBOX");
        assertEquals("ERROR", r.status());
        assertNull(r.confirmationNumber());
        assertTrue(r.message().contains("401"), r.message());
        assertTrue(r.message().contains("250002"), r.message());
        assertTrue(r.message().contains("Invalid Authentication Information"), r.message());
        assertEquals(UPS_401, r.rawResponse());
    }

    @Test
    void fedex_400_surfaces_reason() {
        respondWith(HttpStatus.BAD_REQUEST, FEDEX_400);
        PickupResult r = new FedExConnector(new CarrierProperties(), new ObjectMapper(), new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        }).schedulePickup(request(), "real-fedex-token", "SANDBOX");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("400"), r.message());
        assertTrue(r.message().contains("no valid FedEx Ground services"), r.message());
        assertEquals(FEDEX_400, r.rawResponse());
    }
}
