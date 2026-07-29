package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit-fix #4 — routing rule integration in CarrierServiceImpl uses
 * CountryRegions.regionOf() to feed the routing evaluator's destRegion
 * field. Guarding the taxonomy here prevents silent drift from the
 * frontend REGIONS list in {@code multiship-react/src/utils/countries.ts},
 * which would let region-scoped rules stop firing again.
 */
class CountryRegionsTest {

    @Test
    void regionOf_knownCountries() {
        assertEquals("North America", CountryRegions.regionOf("US"));
        assertEquals("North America", CountryRegions.regionOf("CA"));
        assertEquals("North America", CountryRegions.regionOf("MX"));

        assertEquals("Europe", CountryRegions.regionOf("GB"));
        assertEquals("Europe", CountryRegions.regionOf("DE"));
        assertEquals("Europe", CountryRegions.regionOf("FR"));

        assertEquals("Asia", CountryRegions.regionOf("JP"));
        assertEquals("Asia", CountryRegions.regionOf("CN"));
        assertEquals("Asia", CountryRegions.regionOf("IN"));

        assertEquals("Oceania", CountryRegions.regionOf("AU"));
        assertEquals("Oceania", CountryRegions.regionOf("NZ"));

        assertEquals("South America", CountryRegions.regionOf("BR"));
        assertEquals("Africa", CountryRegions.regionOf("ZA"));
        assertEquals("Middle East", CountryRegions.regionOf("AE"));
    }

    @Test
    void regionOf_isCaseInsensitive() {
        assertEquals("North America", CountryRegions.regionOf("us"));
        assertEquals("Europe", CountryRegions.regionOf("gb"));
        assertEquals("Europe", CountryRegions.regionOf(" DE "));
    }

    @Test
    void regionOf_nullOrBlank_returnsOther() {
        assertEquals("Other", CountryRegions.regionOf(null));
        assertEquals("Other", CountryRegions.regionOf(""));
        assertEquals("Other", CountryRegions.regionOf("   "));
    }

    @Test
    void regionOf_unknownCode_returnsOther() {
        assertEquals("Other", CountryRegions.regionOf("ZZ"));
        assertEquals("Other", CountryRegions.regionOf("XX"));
    }

    @Test
    void regionsList_matchesFrontendTaxonomy() {
        // Must stay in sync with multiship-react/src/utils/countries.ts REGIONS.
        // Same names + same ordering — routing rule filters compare on the
        // exact string label.
        assertEquals(
                java.util.List.of("North America", "Europe", "Middle East",
                        "Asia", "Oceania", "South America", "Africa", "Other"),
                CountryRegions.REGIONS);
    }
}
