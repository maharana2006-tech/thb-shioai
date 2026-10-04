package com.multiship.backend.service.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V114 follow-up — CarrierCallContext puts order+tracking in MDC for the
 * duration of the block and restores the prior state on close. Nested
 * blocks must restore the outer values, not clear everything.
 */
class CarrierCallContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void forOrderPopulatesMdcInsideBlockAndClearsAfter() {
        try (var ignored = CarrierCallContext.forOrder(1234L, "1Z999")) {
            assertEquals("1234", MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
            assertEquals("1Z999", MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
        }
        assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
        assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
    }

    @Test
    void nestedBlockRestoresOuterValuesOnClose() {
        try (var outer = CarrierCallContext.forOrder(1L, "T1")) {
            try (var inner = CarrierCallContext.forOrder(2L, "T2")) {
                assertEquals("2", MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
                assertEquals("T2", MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
            }
            // Inner block's close() must restore outer values, not wipe them.
            assertEquals("1", MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
            assertEquals("T1", MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
        }
        assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
        assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
    }

    @Test
    void nullOrderDoesNotWriteToMdc() {
        try (var ignored = CarrierCallContext.forOrder(null, null)) {
            assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
            assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
        }
    }

    @Test
    void blankTrackingIsIgnored() {
        try (var ignored = CarrierCallContext.forOrder(42L, "   ")) {
            assertEquals("42", MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
            assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
        }
    }

    @Test
    void orderOnlyOverloadLeavesTrackingUntagged() {
        try (var ignored = CarrierCallContext.forOrder(7L)) {
            assertEquals("7", MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
            assertNull(MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING));
        }
    }
}
