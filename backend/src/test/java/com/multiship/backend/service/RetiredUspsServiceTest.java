package com.multiship.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the retired-USPS-service pre-check vocabulary. Catalog-side
 *  disable (shipping_service.enabled=false) is the primary gate; this
 *  helper is belt-and-braces for stale caches / external callers still
 *  sending the retired code. */
class RetiredUspsServiceTest {

    @Test
    void firstClassIntl_returnsPriorityIntlReplacement() {
        String out = ShipmentValidationService.retiredUspsServiceReplacement("USPS", "FIRST_CLASS_INTL");
        assertNotNull(out);
        assertTrue(out.contains("PRIORITY_INTL"), "replacement must name PRIORITY_INTL: " + out);
    }

    @Test
    void wireFormFcpis_alsoCaught() {
        // External callers may send SERA's wire-format code directly.
        String out = ShipmentValidationService.retiredUspsServiceReplacement(
                "USPS", "usps_first_class_package_international_service");
        assertNotNull(out);
    }

    @Test
    void stampsAliasForUsps_sameTreatment() {
        assertNotNull(ShipmentValidationService.retiredUspsServiceReplacement(
                "STAMPS", "FIRST_CLASS_INTL"));
    }

    @Test
    void liveServices_returnNull() {
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement("USPS", "PRIORITY_INTL"));
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement("USPS", "GROUND_ADVANTAGE"));
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement("USPS", "PRIORITY"));
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement("USPS", "EXPRESS_INTL"));
    }

    @Test
    void nonUspsCarriers_bypassCheck() {
        // FedEx/UPS/DHL have their own catalogs; this helper is USPS-only.
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement("FEDEX", "FIRST_CLASS_INTL"));
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement("UPS", "FIRST_CLASS_INTL"));
    }

    @Test
    void nullAndBlankInputsReturnNull() {
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement("USPS", null));
        assertNull(ShipmentValidationService.retiredUspsServiceReplacement(null, "FIRST_CLASS_INTL"));
    }

    @Test
    void caseAndWhitespaceTolerant() {
        assertNotNull(ShipmentValidationService.retiredUspsServiceReplacement("usps", "first_class_intl"));
        assertNotNull(ShipmentValidationService.retiredUspsServiceReplacement("USPS", "  FIRST_CLASS_INTL  "));
    }
}
