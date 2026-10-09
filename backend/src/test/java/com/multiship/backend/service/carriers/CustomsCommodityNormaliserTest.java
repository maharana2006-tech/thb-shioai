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

    // ========== contents_description normalisation (CN22 form field) ==========

    @Test
    void contentsDescription_stripsCommas_theReal933787Fix() {
        // SERA error 4522242 on "Carbon road bicycle frame, unassembled"
        String out = CustomsCommodityNormaliser.normaliseContentsDescription(
                "Carbon road bicycle frame, unassembled");
        assertNotNull(out);
        assertFalse(out.contains(","), "comma must be stripped from CN22 contents_description: " + out);
        assertTrue(out.length() <= CustomsCommodityNormaliser.CONTENTS_DESCRIPTION_MAX_CHARS);
    }

    @Test
    void contentsDescription_stripsOtherListSeparators() {
        assertFalse(CustomsCommodityNormaliser.normaliseContentsDescription("a;b|c/d\\e").contains(";"));
        assertFalse(CustomsCommodityNormaliser.normaliseContentsDescription("a;b|c/d\\e").contains("|"));
    }

    @Test
    void contentsDescription_truncatesTo50Chars() {
        String long60 = "a".repeat(60);
        assertEquals(50, CustomsCommodityNormaliser.normaliseContentsDescription(long60).length());
    }

    @Test
    void contentsDescription_collapsesWhitespace() {
        assertEquals("foo bar baz",
                CustomsCommodityNormaliser.normaliseContentsDescription("foo    bar\t\tbaz"));
    }

    @Test
    void contentsDescription_dropsNonAscii() {
        // SERA/USPS CN22 prints Latin-1 only.
        String out = CustomsCommodityNormaliser.normaliseContentsDescription("café résumé");
        assertNotNull(out);
        assertFalse(out.contains("é"));
    }

    @Test
    void contentsDescription_blankReturnsNull() {
        assertNull(CustomsCommodityNormaliser.normaliseContentsDescription(null));
        assertNull(CustomsCommodityNormaliser.normaliseContentsDescription(""));
        assertNull(CustomsCommodityNormaliser.normaliseContentsDescription("   "));
        assertNull(CustomsCommodityNormaliser.normaliseContentsDescription(",,,"));
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
