package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sprint 48 B10 — customer-facing order-number formatting. */
class OrderNumberFormatterTest {

    @Test
    void manualOrderGetsManPrefix() {
        assertEquals("MAN900001", OrderNumberFormatter.format(900001, "Y"));
        assertEquals("MAN900001", OrderNumberFormatter.format(900001, "y"));
        assertEquals("MAN900001", OrderNumberFormatter.format(900001, " Y "));
    }

    @Test
    void nonManualOrderShowsRawNumber() {
        assertEquals("12345", OrderNumberFormatter.format(12345, "N"));
        assertEquals("12345", OrderNumberFormatter.format(12345, null));
        assertEquals("12345", OrderNumberFormatter.format(12345, ""));
        assertEquals("12345", OrderNumberFormatter.format(12345, "n"));
    }

    @Test
    void nullOrderNoReturnsEmpty() {
        assertEquals("", OrderNumberFormatter.format(null, "Y"));
        assertEquals("", OrderNumberFormatter.format(null, null));
    }

    @Test
    void suffixOverload_zeroOrNullSuffixOmitted() {
        assertEquals("MAN900001", OrderNumberFormatter.format(900001, 0, "Y"));
        assertEquals("MAN900001", OrderNumberFormatter.format(900001, null, "Y"));
        assertEquals("12345", OrderNumberFormatter.format(12345, 0, "N"));
    }

    @Test
    void suffixOverload_nonZeroSuffixAppended() {
        assertEquals("MAN900001-2", OrderNumberFormatter.format(900001, 2, "Y"));
        assertEquals("12345-3", OrderNumberFormatter.format(12345, 3, "N"));
    }
}
