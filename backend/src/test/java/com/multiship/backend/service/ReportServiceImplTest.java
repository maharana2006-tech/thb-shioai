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
}
