package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-territory normalization + per-territory service allowlist. The
 * FE (utils/usTerritoryServices.ts) mirrors this exact matrix — when
 * either side changes, both must move together or the FE will show an
 * operator a service the backend rejects a hop later.
 *
 * <p>Anchoring bug the allowlist fixes: operator ships US → VI and
 * picks UPS 2nd Day Air (02). UPS returns 121100 "The requested service
 * is invalid for the shipment origin". The allowlist hides service 02
 * on the FE and fast-fails at CarrierServiceImpl before the wire call
 * on the backend.
 */
class UsTerritoryNormalizerTest {

    // ─── normalizeCountryCode + isUsTerritory ───────────────────────────

    @Test
    void normalizeCountryCode_usPr_returnsPr() {
        assertEquals("PR", UsTerritoryNormalizer.normalizeCountryCode("US", "PR"));
    }

    @Test
    void normalizeCountryCode_usVi_returnsVi() {
        assertEquals("VI", UsTerritoryNormalizer.normalizeCountryCode("US", "VI"));
    }

    @Test
    void normalizeCountryCode_blankCountry_treatedAsUs() {
        assertEquals("PR", UsTerritoryNormalizer.normalizeCountryCode("", "PR"));
        assertEquals("PR", UsTerritoryNormalizer.normalizeCountryCode(null, "PR"));
    }

    @Test
    void normalizeCountryCode_nonUsCountry_passesThrough() {
        // "CA" + state PR shouldn't rewrite — this isn't a US territory.
        assertEquals("CA", UsTerritoryNormalizer.normalizeCountryCode("CA", "PR"));
    }

    @Test
    void normalizeCountryCode_normalUsState_passesThrough() {
        assertEquals("US", UsTerritoryNormalizer.normalizeCountryCode("US", "CA"));
        assertEquals("US", UsTerritoryNormalizer.normalizeCountryCode("US", "NY"));
    }

    @Test
    void isUsTerritory_allSixTerritories() {
        for (String t : new String[] {"PR", "VI", "GU", "AS", "MP", "UM"}) {
            assertTrue(UsTerritoryNormalizer.isUsTerritory("US", t), "US/" + t);
        }
    }

    @Test
    void isUsTerritory_normalStates_false() {
        assertFalse(UsTerritoryNormalizer.isUsTerritory("US", "CA"));
        assertFalse(UsTerritoryNormalizer.isUsTerritory("US", "NY"));
        assertFalse(UsTerritoryNormalizer.isUsTerritory("US", null));
    }

    // ─── isServiceAllowedForTerritory — PR (mixed domestic + intl) ──────

    @Test
    void pr_ups_domesticAir_allowed() {
        for (String code : new String[] {"01", "02", "13", "14", "59"}) {
            assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "UPS", code),
                    "PR/UPS/" + code);
        }
    }

    @Test
    void pr_ups_worldwideFamily_denied() {
        // Operator confirmed 2026-09-08: UPS Rating API rejects Worldwide
        // services (07/08/54/65) for US → PR with 121100 "service invalid
        // for the shipment origin" — even though older docs suggested
        // both families valid. PR moves on the UPS domestic network only.
        for (String code : new String[] {"07", "08", "54", "65"}) {
            assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "UPS", code),
                    "PR/UPS/" + code);
        }
    }

    @Test
    void pr_ups_groundFamily_denied() {
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "UPS", "03"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "UPS", "11"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "UPS", "12"));
    }

    @Test
    void pr_fedex_intlFamily_allowed() {
        // FedEx PR = INTL network (asymmetric with UPS which uses
        // domestic Air). Operator confirmed 2026-09-08: FedEx rejects
        // domestic services for US → PR with "This service type is
        // not available for the destination."
        for (String code : new String[] {
                "INTERNATIONAL_PRIORITY", "INTERNATIONAL_ECONOMY",
                "INTERNATIONAL_FIRST", "INTERNATIONAL_PRIORITY_EXPRESS"
        }) {
            assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "FEDEX", code),
                    "PR/FEDEX/" + code);
        }
    }

    @Test
    void pr_fedex_domesticExpress_denied() {
        for (String code : new String[] {
                "PRIORITY_OVERNIGHT", "STANDARD_OVERNIGHT", "FIRST_OVERNIGHT",
                "FEDEX_2_DAY", "FEDEX_2_DAY_AM", "FEDEX_EXPRESS_SAVER"
        }) {
            assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "FEDEX", code),
                    "PR/FEDEX/" + code);
        }
    }

    @Test
    void pr_fedex_groundFamily_denied() {
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "FEDEX", "FEDEX_GROUND"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "FEDEX", "GROUND_HOME_DELIVERY"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "FEDEX", "SMART_POST"));
    }

    // ─── isServiceAllowedForTerritory — VI (bug case) ───────────────────

    @Test
    void vi_ups_domesticAir_denied_bugCase() {
        // The reported bug — operator picks UPS 2nd Day Air (02) for VI
        // and UPS returns 121100 "service invalid for origin". Filter
        // must reject 02.
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "UPS", "02"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "UPS", "01"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "UPS", "13"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "UPS", "14"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "UPS", "59"));
    }

    @Test
    void vi_ups_worldwideFamily_allowed() {
        for (String code : new String[] {"07", "08", "54", "65"}) {
            assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "UPS", code),
                    "VI/UPS/" + code);
        }
    }

    @Test
    void vi_fedex_domesticServices_denied() {
        for (String code : new String[] {
                "PRIORITY_OVERNIGHT", "STANDARD_OVERNIGHT", "FEDEX_2_DAY",
                "FEDEX_GROUND", "GROUND_HOME_DELIVERY"
        }) {
            assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "FEDEX", code),
                    "VI/FEDEX/" + code);
        }
    }

    @Test
    void vi_fedex_intlFamily_allowed() {
        for (String code : new String[] {
                "INTERNATIONAL_PRIORITY", "INTERNATIONAL_ECONOMY", "INTERNATIONAL_FIRST"
        }) {
            assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "FEDEX", code),
                    "VI/FEDEX/" + code);
        }
    }

    // ─── isServiceAllowedForTerritory — GU / AS / MP / UM ───────────────

    @Test
    void pacificTerritories_intlOnly() {
        for (String territory : new String[] {"GU", "AS", "MP", "UM"}) {
            assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory(territory, "UPS", "02"),
                    territory + " should deny UPS 02");
            assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory(territory, "UPS", "07"),
                    territory + " should allow UPS 07");
            assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory(territory, "FEDEX", "FEDEX_2_DAY"),
                    territory + " should deny FEDEX_2_DAY");
            assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory(territory, "FEDEX", "INTERNATIONAL_PRIORITY"),
                    territory + " should allow INTERNATIONAL_PRIORITY");
        }
    }

    // ─── isServiceAllowedForTerritory — pass-through cases ──────────────

    @Test
    void nonTerritory_passesThrough() {
        // Not a US territory — no filter applies, everything allowed.
        assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("US", "UPS", "03"));
        assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("CA", "FEDEX", "FEDEX_GROUND"));
        assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory(null, "UPS", "01"));
    }

    @Test
    void nullServiceCode_passesThrough() {
        assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "UPS", null));
        assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", null, "01"));
    }

    // ─── isServiceAllowedForTerritory — DHL / USPS fallback ─────────────

    @Test
    void dhlUsps_fallBackToGroundDenylist() {
        // No per-carrier allowlist for DHL / USPS — fallback is legacy
        // ground-family denylist. Non-ground codes allowed; ground
        // codes still denied.
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "DHL", "03"));
        assertFalse(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "USPS", "FEDEX_GROUND"));
        assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("PR", "DHL", "EXPRESS_WORLDWIDE"));
        assertTrue(UsTerritoryNormalizer.isServiceAllowedForTerritory("VI", "USPS", "PRIORITY_MAIL"));
    }
}
