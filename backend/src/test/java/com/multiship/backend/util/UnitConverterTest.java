package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Golden-value tests for the four carrier-facing conversion entry points.
 * Numbers double as documentation of what the carrier sees on the wire.
 */
class UnitConverterTest {

    @Test
    void kgToPoundsRoundsToTwoDp() {
        // 1.5 kg = 3.30693393 lb → 3.31 rounded HALF_UP
        assertEquals(new BigDecimal("3.31"), UnitConverter.toPounds(new BigDecimal("1.5"), "KG"));
        assertEquals(new BigDecimal("3.31"), UnitConverter.toPounds(new BigDecimal("1.5"), "kg"));
        assertEquals(new BigDecimal("3.31"), UnitConverter.toPounds(new BigDecimal("1.5"), " KGS "));
    }

    @Test
    void lbToPoundsRoundTripsUnchanged() {
        assertEquals(new BigDecimal("1.50"), UnitConverter.toPounds(new BigDecimal("1.5"), "LB"));
        assertEquals(new BigDecimal("1.50"), UnitConverter.toPounds(new BigDecimal("1.5"), null));
    }

    @Test
    void kgToOuncesForUspsUsesSwsimUnit() {
        // 1.5 kg = 52.91 oz → SWSIM's WeightOz field
        assertEquals(new BigDecimal("52.91"), UnitConverter.toOunces(new BigDecimal("1.5"), "KG"));
        // 1 lb = 16 oz exactly
        assertEquals(new BigDecimal("16.00"), UnitConverter.toOunces(BigDecimal.ONE, "LB"));
    }

    @Test
    void cmToInchesRoundsToThreeDp() {
        // 10 cm = 3.93700787 in → 3.937 rounded HALF_UP
        assertEquals(new BigDecimal("3.937"), UnitConverter.toInches(BigDecimal.TEN, "CM"));
    }

    @Test
    void inToCentimeters() {
        // 12 in = 30.48 cm exactly
        assertEquals(new BigDecimal("30.480"), UnitConverter.toCentimeters(new BigDecimal("12"), "IN"));
    }

    @Test
    void nullInputReturnsNullEverywhere() {
        assertNull(UnitConverter.toPounds(null, "KG"));
        assertNull(UnitConverter.toKilograms(null, "LB"));
        assertNull(UnitConverter.toOunces(null, "KG"));
        assertNull(UnitConverter.toInches(null, "CM"));
        assertNull(UnitConverter.toCentimeters(null, "IN"));
    }

    @Test
    void unknownUnitFailsFastRatherThanSilentlyAssumingLb() {
        assertThrows(IllegalArgumentException.class,
                () -> UnitConverter.toPounds(BigDecimal.ONE, "STONE"));
    }

    @Test
    void preferredUnitByCarrier() {
        assertEquals("OZ", UnitConverter.preferredWeightUnit("USPS"));
        assertEquals("LB", UnitConverter.preferredWeightUnit("UPS"));
        assertEquals("LB", UnitConverter.preferredWeightUnit("FEDEX"));
        assertEquals("LB", UnitConverter.preferredWeightUnit(null));
    }
}
