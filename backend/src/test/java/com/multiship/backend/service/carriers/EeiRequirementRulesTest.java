package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch-6 post-mortem test coverage (2026-09-12) — the 14 EEI-failed
 * orders in that batch all shipped US→CN. EEI rules must catch that
 * shape at import time.
 */
class EeiRequirementRulesTest {

    @Test
    void usToChinaWithoutFtrOrAesIsRejected() {
        // Real batch-6 shape: US-origin (default), CN destination, no
        // FTR / no AES. FedEx rejects with SHIPMENTVALIDATION.EEIEDIT.ERROR.
        String err = EeiRequirementRules.check("US", "CN", null, null);
        assertNotNull(err, "US->CN without EEI must be flagged pre-flight");
        assertTrue(err.contains("CN"), "message names the destination: " + err);
        assertTrue(err.contains("FTR") || err.contains("AES"),
                "message points at the missing filing: " + err);
        assertTrue(err.contains("EEIEDIT"), "message names the FedEx error: " + err);
        assertTrue(err.contains("manual") || err.contains("Data History"),
                "message suggests the fix path: " + err);
    }

    @Test
    void usToChinaWithFtrExemptionIsFine() {
        // Operator supplied an FTR exemption code — satisfies the rule
        // even if FedEx's own downstream check of the code's validity
        // later rejects. Import-side validator can't verify the code
        // itself, only that SOMETHING was supplied.
        assertNull(EeiRequirementRules.check("US", "CN", "NO_EEI_30_37_A", null));
    }

    @Test
    void usToChinaWithAesCitationIsFine() {
        assertNull(EeiRequirementRules.check("US", "CN", null, "AES X20260912123456"));
    }

    @Test
    void usToChinaWithBothIsFine() {
        assertNull(EeiRequirementRules.check("US", "CN", "NO_EEI_30_37_A", "AES X20260912123456"));
    }

    @Test
    void otherStrategicCountriesAreCaught() {
        assertNotNull(EeiRequirementRules.check("US", "RU", null, null));
        assertNotNull(EeiRequirementRules.check("US", "IR", null, null));
        assertNotNull(EeiRequirementRules.check("US", "KP", null, null));
        assertNotNull(EeiRequirementRules.check("US", "CU", null, null));
        assertNotNull(EeiRequirementRules.check("US", "SY", null, null));
    }

    @Test
    void usToFriendlyDestinationsIsFine() {
        // Countries NOT in the strategic list — no unconditional EEI
        // required (FedEx applies the $2,500 monetary threshold on its
        // own edge; we let it).
        assertNull(EeiRequirementRules.check("US", "GB", null, null));
        assertNull(EeiRequirementRules.check("US", "DE", null, null));
        assertNull(EeiRequirementRules.check("US", "CA", null, null));
        assertNull(EeiRequirementRules.check("US", "MX", null, null));
        assertNull(EeiRequirementRules.check("US", "IN", null, null));
    }

    @Test
    void nonUsOriginIsNotChecked() {
        // Non-US origins have their own export-declaration regimes; US
        // EEI rules don't apply. ExportDeclarationPolicyRegistry handles
        // the CA / GB / EU / etc. side.
        assertNull(EeiRequirementRules.check("CA", "CN", null, null));
        assertNull(EeiRequirementRules.check("GB", "CN", null, null));
    }

    @Test
    void blankAndNullOriginTreatedAsUs() {
        // Bulk-import rows don't carry origin (backend defaults to platform
        // ship-from, which is US-based for this tenant). Blank / null
        // origin must be treated as US for the check.
        assertNotNull(EeiRequirementRules.check(null, "CN", null, null));
        assertNotNull(EeiRequirementRules.check("", "CN", null, null));
        assertNotNull(EeiRequirementRules.check("  ", "CN", null, null));
    }

    @Test
    void blankAndNullDestinationIsSafe() {
        // Validation of destination country happens elsewhere in
        // validateRow; the EEI check must not double-fire on blank.
        assertNull(EeiRequirementRules.check("US", null, null, null));
        assertNull(EeiRequirementRules.check("US", "", null, null));
    }

    @Test
    void caseInsensitiveDestination() {
        // ISO codes should always be upper but we're defensive.
        assertNotNull(EeiRequirementRules.check("US", "cn", null, null));
        assertNotNull(EeiRequirementRules.check("US", " CN ", null, null));
    }

    @Test
    void blankFtrAndBlankAesTriggerRule() {
        // Whitespace-only values must NOT satisfy the rule — the fields
        // must contain something FedEx can parse.
        assertNotNull(EeiRequirementRules.check("US", "CN", "", ""));
        assertNotNull(EeiRequirementRules.check("US", "CN", "   ", "   "));
    }

    @Test
    void destinationLookupHelper() {
        assertTrue(EeiRequirementRules.isEeiAlwaysRequiredDestination("CN"));
        assertTrue(EeiRequirementRules.isEeiAlwaysRequiredDestination("cn"));
        assertTrue(EeiRequirementRules.isEeiAlwaysRequiredDestination("RU"));
        // Countries not in the strategic list return false.
        org.junit.jupiter.api.Assertions.assertFalse(
                EeiRequirementRules.isEeiAlwaysRequiredDestination("GB"));
        org.junit.jupiter.api.Assertions.assertFalse(
                EeiRequirementRules.isEeiAlwaysRequiredDestination(null));
    }
}
