package com.multiship.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 45 — CSV quoting semantics. The core service is exercised via
 * the report endpoints on the full suite; the escaping is verified here
 * since it's the fiddly bit.
 */
class ReportServiceImplTest {

    @Test
    void csv_nullReturnsEmpty() {
        assertEquals("", ReportServiceImpl.csv(null));
    }

    @Test
    void csv_plainStringIsPassThrough() {
        assertEquals("hello", ReportServiceImpl.csv("hello"));
    }

    @Test
    void csv_stringWithCommaIsQuoted() {
        assertEquals("\"Doe, John\"", ReportServiceImpl.csv("Doe, John"));
    }

    @Test
    void csv_stringWithQuoteIsQuotedAndEscaped() {
        assertEquals("\"say \"\"hi\"\"\"", ReportServiceImpl.csv("say \"hi\""));
    }

    @Test
    void csv_stringWithNewlineIsQuoted() {
        assertEquals("\"line1\nline2\"", ReportServiceImpl.csv("line1\nline2"));
    }

    @Test
    void csv_bigDecimalToPlainString() {
        assertEquals("42.00", ReportServiceImpl.csv(new BigDecimal("42.00")));
    }

    @Test
    void csv_localDateTimeIsIso() {
        assertEquals("2026-07-28T12:00", ReportServiceImpl.csv(LocalDateTime.of(2026, 7, 28, 12, 0)));
    }

    @Test
    void csv_localDateIsIso() {
        assertEquals("2026-07-28", ReportServiceImpl.csv(LocalDate.of(2026, 7, 28)));
    }

    // ===== Audit-fix #6 — typedRow fail-fast on column drift =====

    @Test
    void typedRow_correctLengthPassesThrough() {
        Object[] row = { 1, "hello", null, 42.0 };
        assertArrayEquals(row, ReportServiceImpl.typedRow(row, 4));
    }

    @Test
    void typedRow_extraColumnThrows() {
        Object[] row = { 1, 2, 3, 4, 5 };
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReportServiceImpl.typedRow(row, 4));
        assertTrue(ex.getMessage().contains("5 columns"),
                "message should surface the actual column count: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("expected 4"),
                "message should surface the expected count: " + ex.getMessage());
    }

    @Test
    void typedRow_missingColumnThrows() {
        Object[] row = { 1, 2, 3 };
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReportServiceImpl.typedRow(row, 4));
        assertTrue(ex.getMessage().contains("3 columns"));
        assertTrue(ex.getMessage().contains("column-order drift"));
    }

    @Test
    void typedRow_scalarInsteadOfArrayThrows() {
        // Native SQL that projects a single column (COUNT(*)) returns a
        // scalar, not Object[]. If a caller wires a 1-column report and
        // then adds a second column later, this catches the misuse.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReportServiceImpl.typedRow(42L, 4));
        assertTrue(ex.getMessage().contains("scalar"));
    }
}
