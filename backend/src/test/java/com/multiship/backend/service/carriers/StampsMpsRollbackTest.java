package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Sprint 49 Tier 2 Fix 4 — Stamps MPS partial-success rollback.
 *
 * <p>Before this fix, a 3-package Stamps shipment where package 3
 * failed left pieces 1 + 2 as billable real labels at the carrier
 * while package 3's failure surfaced as an error (previously as a
 * fake tracking number). Ops had to reconcile manually.
 *
 * <p>Now the connector emits CancelIndicium for every successful
 * piece before re-throwing the failure. Best-effort: cancel-failures
 * are logged but do NOT mask the original create failure.
 */
class StampsMpsRollbackTest {

    @Test
    void rollbackWithEmptyListIsNoOp() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        assertDoesNotThrow(() ->
                c.rollbackSuccessfulPieces(List.of(), "any-token", "SANDBOX"));
        assertDoesNotThrow(() ->
                c.rollbackSuccessfulPieces(null, "any-token", "SANDBOX"));
    }

    @Test
    void rollbackSkipsPiecesWithoutTrackingNumber() {
        // Overriding voidShipment lets us verify the rollback picks the
        // right subset without making real SWSIM calls.
        AtomicInteger calls = new AtomicInteger();
        List<String> tracked = new ArrayList<>();
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper()) {
            @Override
            public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                            String accountNumber, String senderCountryCode) {
                calls.incrementAndGet();
                tracked.add(trackingNumber);
                return new VoidResult(trackingNumber, true, "VOIDED", "ok", null);
            }
        };

        // Two real pieces + one null-tracking piece (parser edge case)
        ShipmentResult p1 = new ShipmentResult("9400111111111111", null, null, null, null, null, null);
        ShipmentResult pNull = new ShipmentResult(null, null, null, null, null, null, null);
        ShipmentResult p3 = new ShipmentResult("9400222222222222", null, null, null, null, null, null);

        c.rollbackSuccessfulPieces(java.util.Arrays.asList(p1, pNull, p3),
                "stamps-local-token", "SANDBOX");

        // Two cancels, one skipped for missing tracking
        assertEquals(2, calls.get());
        assertTrue(tracked.contains("9400111111111111"));
        assertTrue(tracked.contains("9400222222222222"));
    }

    @Test
    void rollbackFailuresAreSwallowed() {
        // The original createShipment failure is what the caller sees;
        // secondary CancelIndicium failures must not throw over it.
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper()) {
            @Override
            public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                            String accountNumber, String senderCountryCode) {
                throw new RuntimeException("Stamps unreachable");
            }
        };
        ShipmentResult p1 = new ShipmentResult("9400111", null, null, null, null, null, null);
        assertDoesNotThrow(() ->
                c.rollbackSuccessfulPieces(List.of(p1), "any-token", "SANDBOX"));
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static void assertTrue(boolean cond) {
        org.junit.jupiter.api.Assertions.assertTrue(cond);
    }
}
