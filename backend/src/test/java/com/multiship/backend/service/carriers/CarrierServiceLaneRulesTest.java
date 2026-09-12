package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Batch #8 post-mortem cases (2026-09-12): pre-flight lane
 * check must catch the three carrier-rejection shapes we saw in that
 * batch's 25 errored orders — UPS 3 Day Select to AK/HI, FedEx Express
 * Saver to AK/HI, and (via extension) FedEx Ground to AK/HI. Fine on
 * lanes the carriers do cover; silent on non-US destinations and on
 * pairs we don't have a rule for.
 */
class CarrierServiceLaneRulesTest {

    @Test
    void ups3DaySelectToAlaskaIsRejectedWithActionableMessage() {
        // Real failing row from Batch #8: order 902175, shipvia_cd=12,
        // shipto_state=AK, shipto_zip=99501. UPS 121210 at label time.
        String err = CarrierServiceLaneRules.checkLane("UPS", "12", "AK", "US");
        assertNotNull(err, "UPS 3 Day Select (12) to AK must be rejected pre-flight");
        assertTrue(err.contains("3 Day Select"), "message names the service: " + err);
        assertTrue(err.contains("AK"), "message names the state: " + err);
        assertTrue(err.contains("2nd Day Air") || err.contains("Next Day Air"),
                "message suggests an alternative: " + err);
        assertTrue(err.contains("121210"), "message names the UPS error code: " + err);
    }

    @Test
    void ups3DaySelectToHawaiiIsRejected() {
        // Real failing row: order 902754, shipvia_cd=12, shipto_state=HI.
        String err = CarrierServiceLaneRules.checkLane("UPS", "12", "HI", "US");
        assertNotNull(err);
        assertTrue(err.contains("HI"));
    }

    @Test
    void upsGroundToAlaskaIsRejected() {
        // Not in Batch #8 but same class of error — UPS Ground 48-only.
        assertNotNull(CarrierServiceLaneRules.checkLane("UPS", "03", "AK", "US"));
        assertNotNull(CarrierServiceLaneRules.checkLane("UPS", "GROUND", "AK", "US"));
    }

    @Test
    void ups2ndDayAirToAlaskaIsFine() {
        // 02 = 2nd Day Air, one of the recommended replacements.
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "02", "AK", "US"),
                "2nd Day Air covers AK — must NOT be rejected");
    }

    @Test
    void upsNextDayAirToHawaiiIsFine() {
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "01", "HI", "US"));
    }

    @Test
    void upsGroundToContiguousUsIsFine() {
        // Ground to a 48-state destination is the base case.
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "03", "NY", "US"));
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "03", "CA", "US"));
    }

    @Test
    void fedexExpressSaverToAlaskaIsRejected() {
        // Real failing row: order 901683, service=FEDEX_EXPRESS_SAVER,
        // shipto_state=AK. FedEx SERVICETYPE.NOTSUPPORTED at label time.
        String err = CarrierServiceLaneRules.checkLane("FEDEX", "FEDEX_EXPRESS_SAVER", "AK", "US");
        assertNotNull(err, "FedEx Express Saver to AK must be rejected pre-flight");
        assertTrue(err.contains("Express Saver"), "message names the service: " + err);
        assertTrue(err.contains("AK"), "message names the state: " + err);
        assertTrue(err.contains("2Day") || err.contains("Overnight"),
                "message suggests an alternative: " + err);
        assertTrue(err.contains("NOTSUPPORTED"), "message names the FedEx error: " + err);
    }

    @Test
    void fedexExpressSaverToHawaiiIsRejected() {
        // Real failing row: order 902388, service=FEDEX_EXPRESS_SAVER,
        // shipto_state=HI.
        assertNotNull(CarrierServiceLaneRules.checkLane("FEDEX", "FEDEX_EXPRESS_SAVER", "HI", "US"));
    }

    @Test
    void fedexExpressSaverToPuertoRicoIsFine() {
        // Express Saver does cover PR, unlike Ground. NON_AK_HI_SERVICES
        // is specifically AK/HI, not PR.
        assertNull(CarrierServiceLaneRules.checkLane("FEDEX", "FEDEX_EXPRESS_SAVER", "PR", "US"));
    }

    @Test
    void fedexGroundToAlaskaIsRejected() {
        assertNotNull(CarrierServiceLaneRules.checkLane("FEDEX", "FEDEX_GROUND", "AK", "US"));
        assertNotNull(CarrierServiceLaneRules.checkLane("FEDEX", "GROUND_HOME_DELIVERY", "AK", "US"));
        assertNotNull(CarrierServiceLaneRules.checkLane("FEDEX", "SMART_POST", "HI", "US"));
    }

    @Test
    void fedex2DayToAlaskaIsFine() {
        assertNull(CarrierServiceLaneRules.checkLane("FEDEX", "FEDEX_2_DAY", "AK", "US"));
        assertNull(CarrierServiceLaneRules.checkLane("FEDEX", "PRIORITY_OVERNIGHT", "HI", "US"));
    }

    @Test
    void nonUsLanesAreNotChecked() {
        // Rules only encode US carriers' US-lane exclusions. Other
        // countries pass through to the reactive carrier response.
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "03", "ON", "CA"));
        assertNull(CarrierServiceLaneRules.checkLane("FEDEX", "FEDEX_GROUND", "NSW", "AU"));
    }

    @Test
    void unknownServiceIsNotChecked() {
        // A service we don't have a rule for must fall through — we
        // never over-block, only catch known-hard exclusions.
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "UNKNOWN_XYZ", "AK", "US"));
        assertNull(CarrierServiceLaneRules.checkLane("FEDEX", "SOMETHING_NEW", "HI", "US"));
    }

    @Test
    void nullInputsAreSafe() {
        assertNull(CarrierServiceLaneRules.checkLane(null, "03", "AK", "US"));
        assertNull(CarrierServiceLaneRules.checkLane("UPS", null, "AK", "US"));
        // destState may legitimately be blank on intl (some countries
        // don't carry a state on the label) — that must not NPE the
        // intl-lane check.
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "03", null, "GB"),
                "blank state on non-US must not blow up the intl check");
        assertNull(CarrierServiceLaneRules.checkLane("UPS", "03", "AK", null));
    }

    /* ==================== International lane rules (Batch #6) ==================== */

    @Test
    void fedexInternationalFirstToSouthAfricaIsRejected() {
        // Real failing rows from Batch #6: 4 orders on
        // INTERNATIONAL_FIRST to ZA. FedEx SERVICETYPE.NOTSUPPORTED.
        String err = CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_FIRST", "Gauteng", "ZA");
        assertNotNull(err, "INTERNATIONAL_FIRST to ZA must be rejected pre-flight");
        assertTrue(err.contains("International First"), "message names the service: " + err);
        assertTrue(err.contains("ZA"), "message names the country: " + err);
        assertTrue(err.contains("INTERNATIONAL_PRIORITY"),
                "message suggests the fallback service: " + err);
        assertTrue(err.contains("NOTSUPPORTED"), "message names the FedEx error: " + err);
    }

    @Test
    void fedexInternationalFirstToIndiaIsRejected() {
        // 2 batch-6 failures.
        assertNotNull(CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_FIRST", "Delhi", "IN"));
    }

    @Test
    void fedexInternationalFirstToPeruIsRejected() {
        // 2 batch-6 failures.
        assertNotNull(CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_FIRST", "Arequipa", "PE"));
    }

    @Test
    void fedexInternationalFirstToBrazilIsRejected() {
        // 1 batch-6 failure.
        assertNotNull(CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_FIRST", "SP", "BR"));
    }

    @Test
    void fedexInternationalFirstToMexicoIsRejected() {
        // 1 batch-6 failure.
        assertNotNull(CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_FIRST", "JAL", "MX"));
    }

    @Test
    void fedexInternationalFirstToItalyIsFine() {
        // IT had 1 failure in batch 6 but the pattern is ZIP-level
        // (specific cities aren't served) — we intentionally don't block
        // the whole country to avoid false negatives on Milan/Rome
        // which are covered. Reactive carrier response handles the
        // outliers.
        assertNull(CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_FIRST", "MI", "IT"));
    }

    @Test
    void fedexInternationalPriorityToSouthAfricaIsFine() {
        // Priority has broader coverage — must NOT be blocked.
        assertNull(CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_PRIORITY", "Gauteng", "ZA"));
    }

    @Test
    void fedexInternationalEconomyToMexicoIsFine() {
        // Economy is covered; only First is restricted.
        assertNull(CarrierServiceLaneRules.checkLane(
                "FEDEX", "INTERNATIONAL_ECONOMY", "JAL", "MX"));
    }
}
