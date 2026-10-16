package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-076 — locks in the shared CSV cell neutralizer: every leading
 * formula sentinel gets a quote prefix, benign values are untouched, and the
 * prefix composes with RFC-4180 quoting (prefix first, then quote).
 */
class CsvUtilTest {

    @Test
    void nullAndBenignUnchanged() {
        assertEquals("", CsvUtil.csvCell(null));
        assertEquals("plain", CsvUtil.csvCell("plain"));
    }

    @Test
    void everyFormulaSentinelGetsQuotePrefix() {
        assertEquals("'=1+1", CsvUtil.csvCell("=1+1"));
        assertEquals("'+1", CsvUtil.csvCell("+1"));
        assertEquals("'-1", CsvUtil.csvCell("-1"));
        assertEquals("'@SUM(A1)", CsvUtil.csvCell("@SUM(A1)"));
        assertEquals("'\tx", CsvUtil.csvCell("\tx"));
    }

    @Test
    void prefixThenRfc4180QuoteComposes() {
        // leading '=' gets the ' prefix, THEN the comma forces RFC-4180
        // quoting of the already-prefixed value.
        assertEquals("\"'=1,2\"", CsvUtil.csvCell("=1,2"));
    }

    @Test
    void rfc4180QuotingUnchangedForBenignSpecials() {
        assertEquals("\"a,b\"", CsvUtil.csvCell("a,b"));
        assertEquals("\"a\"\"b\"", CsvUtil.csvCell("a\"b"));
    }
}
