package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T10 — pins the customs-field normaliser contract. */
class CustomsCommodityNormaliserTest {

    // ========== HS code ==========

    @Test
    void hsCodeStripsDotsAndSpaces() {
        assertEquals("6109100012", CustomsCommodityNormaliser.normaliseHsCode("6109.10.0012"));
        assertEquals("6109100012", CustomsCommodityNormaliser.normaliseHsCode("6109 10 0012"));
        assertEquals("12345", CustomsCommodityNormaliser.normaliseHsCode("HS12345"));
    }

    @Test
    void hsCodeTruncatesToTenDigits() {
        assertEquals("1234567890",
                CustomsCommodityNormaliser.normaliseHsCode("1234567890123"));
    }

    @Test
    void hsCodeShorterLengthsPassThrough() {
        assertEquals("610910", CustomsCommodityNormaliser.normaliseHsCode("610910"));
        assertEquals("61091000", CustomsCommodityNormaliser.normaliseHsCode("6109.10.00"));
    }

    @Test
    void hsCodeAllLettersReturnsNull() {
        assertNull(CustomsCommodityNormaliser.normaliseHsCode("ABC"));
        assertNull(CustomsCommodityNormaliser.normaliseHsCode(""));
        assertNull(CustomsCommodityNormaliser.normaliseHsCode(null));
    }

    // ========== country of origin ==========

    @Test
    void countryOfOriginAlpha2PassesThrough() {
        assertEquals("US", CustomsCommodityNormaliser.normaliseCountryOfOrigin("US"));
        assertEquals("GB", CustomsCommodityNormaliser.normaliseCountryOfOrigin("gb"));
        assertEquals("DE", CustomsCommodityNormaliser.normaliseCountryOfOrigin("  de  "));
    }

    @Test
    void countryOfOriginAlpha3MapsToAlpha2() {
        assertEquals("US", CustomsCommodityNormaliser.normaliseCountryOfOrigin("USA"));
        assertEquals("GB", CustomsCommodityNormaliser.normaliseCountryOfOrigin("GBR"));
        assertEquals("CA", CustomsCommodityNormaliser.normaliseCountryOfOrigin("CAN"));
        assertEquals("DE", CustomsCommodityNormaliser.normaliseCountryOfOrigin("DEU"));
    }

    @Test
    void countryOfOriginAlpha3HandlesPunctuation() {
        assertEquals("US", CustomsCommodityNormaliser.normaliseCountryOfOrigin("U.S.A."));
        assertEquals("US", CustomsCommodityNormaliser.normaliseCountryOfOrigin("U.S."));
    }

    @Test
    void countryOfOriginFullNamesMap() {
        assertEquals("US", CustomsCommodityNormaliser.normaliseCountryOfOrigin("United States"));
        assertEquals("US", CustomsCommodityNormaliser.normaliseCountryOfOrigin("UNITED STATES OF AMERICA"));
        assertEquals("GB", CustomsCommodityNormaliser.normaliseCountryOfOrigin("United Kingdom"));
        assertEquals("GB", CustomsCommodityNormaliser.normaliseCountryOfOrigin("Great Britain"));
        assertEquals("CA", CustomsCommodityNormaliser.normaliseCountryOfOrigin("Canada"));
        assertEquals("JP", CustomsCommodityNormaliser.normaliseCountryOfOrigin("Japan"));
    }

    @Test
    void countryOfOriginUnrecognisedReturnsNull() {
        assertNull(CustomsCommodityNormaliser.normaliseCountryOfOrigin("Freedonia"));
        assertNull(CustomsCommodityNormaliser.normaliseCountryOfOrigin("XX"));
        assertNull(CustomsCommodityNormaliser.normaliseCountryOfOrigin(""));
        assertNull(CustomsCommodityNormaliser.normaliseCountryOfOrigin(null));
    }

    // ========== description truncation ==========

    @Test
    void descriptionUnderCapPassesThrough() {
        assertEquals("Short", CustomsCommodityNormaliser.truncateDescription("Short"));
    }

    @Test
    void descriptionOverCapTruncatesWithEllipsis() {
        String s = "x".repeat(300);
        String out = CustomsCommodityNormaliser.truncateDescription(s);
        assertEquals(255, out.length());
        assertTrue(out.endsWith("…"));
    }

    @Test
    void descriptionAtCapIsPassThrough() {
        String s = "a".repeat(255);
        assertEquals(s, CustomsCommodityNormaliser.truncateDescription(s));
    }

    @Test
    void descriptionNullReturnsNull() {
        assertNull(CustomsCommodityNormaliser.truncateDescription(null));
    }

    // ========== weight reconciliation ==========

    @Test
    void weightsReconcileWithinTolerance() {
        // 2 × 1kg vs 2kg package = exact → ok
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 2, new BigDecimal("2.0")));
        // 2 × 1kg vs 2.1kg = 95% of package → ok (within +-20%)
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 2, new BigDecimal("2.1")));
        // 2 × 1kg vs 1.9kg = 105% of package → ok
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 2, new BigDecimal("1.9")));
    }

    @Test
    void weightsReconcileOutsideToleranceReturnsFalse() {
        // 1 × 1kg vs 2kg = 50% of package → mismatch
        assertFalse(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 1, new BigDecimal("2.0")));
        // 10 × 1kg vs 2kg = 500% of package → mismatch
        assertFalse(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 10, new BigDecimal("2.0")));
    }

    @Test
    void weightsReconcileMissingInputsReturnTrue() {
        // Any null → cannot check → don't flag
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                null, 2, new BigDecimal("2.0")));
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), null, new BigDecimal("2.0")));
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 2, null));
    }

    @Test
    void weightsReconcileZeroOrNegativeInputsReturnTrue() {
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                BigDecimal.ZERO, 2, new BigDecimal("2.0")));
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 0, new BigDecimal("2.0")));
        assertTrue(CustomsCommodityNormaliser.weightsReconcile(
                new BigDecimal("1.0"), 2, BigDecimal.ZERO));
    }
}
