package com.multiship.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FDX-G1 — Java-side mirror of the V25 backfill SQL that classifies each
 * {@link com.multiship.backend.model.ShippingService} as Express or Ground.
 * Pinned here so future carrier-sync additions (new FedEx/UPS service
 * codes, new carriers) surface as failing tests rather than silently
 * defaulting to Ground and breaking the manifest split.
 *
 * <p>The V25 migration and this Java helper must stay in lockstep:
 * services synced from a carrier availability API AFTER the backfill
 * runs go through {@code classifyExpress}, while pre-existing rows are
 * covered by the migration's UPDATE statements. A drift between the two
 * would silently misclassify newly-synced services.
 */
class ShippingConfigServiceClassifyExpressTest {

    // ===== FedEx =====

    @Test
    void fedex_ground_services_are_not_express() {
        assertFalse(ShippingConfigService.classifyExpress("FEDEX", "FEDEX_GROUND"));
        assertFalse(ShippingConfigService.classifyExpress("FEDEX", "GROUND_HOME_DELIVERY"));
    }

    @Test
    void fedex_air_services_are_express() {
        // Every non-Ground FedEx service. Priority Overnight, 2Day, Express
        // Saver, Standard Overnight, and the intl portfolio all fly on the
        // Express fleet.
        assertTrue(ShippingConfigService.classifyExpress("FEDEX", "PRIORITY_OVERNIGHT"));
        assertTrue(ShippingConfigService.classifyExpress("FEDEX", "STANDARD_OVERNIGHT"));
        assertTrue(ShippingConfigService.classifyExpress("FEDEX", "FEDEX_2_DAY"));
        assertTrue(ShippingConfigService.classifyExpress("FEDEX", "FEDEX_EXPRESS_SAVER"));
        assertTrue(ShippingConfigService.classifyExpress("FEDEX", "INTERNATIONAL_PRIORITY"));
        assertTrue(ShippingConfigService.classifyExpress("FEDEX", "INTERNATIONAL_ECONOMY"));
        assertTrue(ShippingConfigService.classifyExpress("FEDEX", "INTERNATIONAL_FIRST"));
    }

    // ===== UPS =====

    @Test
    void ups_ground_codes_are_not_express() {
        // UPS uses both "03" and zero-padded "003" for Ground. Anything
        // matching either — plus any service name containing GROUND —
        // must classify as Ground so the pickup + manifest fleets align.
        assertFalse(ShippingConfigService.classifyExpress("UPS", "03"));
        assertFalse(ShippingConfigService.classifyExpress("UPS", "003"));
    }

    @Test
    void ups_air_codes_are_express() {
        // 07 = Worldwide Express, 08 = Worldwide Expedited, 11 = UPS
        // Standard (intra-Europe), 12 = 3 Day Select, 13 = Next Day Air
        // Saver, 14 = Next Day Air Early, 54 = Worldwide Express Plus,
        // 59 = 2nd Day Air A.M., 65 = UPS Saver, 96 = Worldwide Express
        // Freight. All Air fleet.
        assertTrue(ShippingConfigService.classifyExpress("UPS", "07"));
        assertTrue(ShippingConfigService.classifyExpress("UPS", "01")); // Next Day Air
        assertTrue(ShippingConfigService.classifyExpress("UPS", "12"));
        assertTrue(ShippingConfigService.classifyExpress("UPS", "65"));
    }

    // ===== DHL =====

    @Test
    void dhl_is_always_express() {
        // DHL Express is single-fleet — every service ships on the same
        // driver, regardless of speed tier. The classifier always returns
        // true so the manifest split treats DHL as a single Express group.
        assertTrue(ShippingConfigService.classifyExpress("DHL", "P"));
        assertTrue(ShippingConfigService.classifyExpress("DHL", "N"));
        assertTrue(ShippingConfigService.classifyExpress("DHL", "T"));   // Time Definite
    }

    // ===== USPS + fallback =====

    @Test
    void usps_is_never_express() {
        // SWSIM has no per-request fleet split at the manifest level.
        // Every USPS row stays false so ManifestServiceImpl skips the
        // Express branch entirely for USPS.
        assertFalse(ShippingConfigService.classifyExpress("USPS", "PRIORITY"));
        assertFalse(ShippingConfigService.classifyExpress("USPS", "PRIORITY_EXPRESS"));
        assertFalse(ShippingConfigService.classifyExpress("USPS", "USPS GA"));
        // Even USPS Priority Mail Express — SWSIM manifest is the same
        // envelope for every USPS service level.
    }

    @Test
    void unknown_carrier_returns_false() {
        // Unknown carriers default to false so ManifestServiceImpl treats
        // them as single-fleet (no Express split) instead of silently
        // classifying them as Express.
        assertFalse(ShippingConfigService.classifyExpress("SOMETHING", "ANY_CODE"));
    }

    @Test
    void null_inputs_return_false() {
        // Defensive — no NPE on partial input at sync time.
        assertFalse(ShippingConfigService.classifyExpress(null, "FEDEX_GROUND"));
        assertFalse(ShippingConfigService.classifyExpress("FEDEX", null));
        assertFalse(ShippingConfigService.classifyExpress(null, null));
    }

    // ===== normalisation =====

    @Test
    void carrier_and_service_code_are_normalised_case_insensitive() {
        // Sync sometimes passes mixed-case input (varies per carrier API).
        // The classifier must not depend on the caller's normalisation.
        assertTrue(ShippingConfigService.classifyExpress("fedex", "priority_overnight"));
        assertFalse(ShippingConfigService.classifyExpress("Fedex", "fedex_ground"));
        assertFalse(ShippingConfigService.classifyExpress("  UPS  ", "  03  "));
    }
}
