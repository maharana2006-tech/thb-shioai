package com.multiship.backend.util;

/**
 * Shared CSV cell neutralizer for human-facing exports. Extracted so every
 * exporter of tenant-authored data gets the same BUG-076 guard instead of a
 * per-file copy that can (and did) drift.
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    /** RFC-4180 CSV field: quote when the value contains ", newline, or a comma.
     *  <p>BUG-076 — CSV formula injection: Excel / Numbers / LibreOffice
     *  treat a cell that starts with =, +, -, @ (or TAB) as a formula.
     *  A client name like "=1+1" would evaluate on open; a payload of
     *  "=HYPERLINK(...)" is the classic exfiltration vector. Prefix a
     *  single quote (which the spreadsheets strip on display) when the
     *  value starts with one of those characters. */
    public static String csvCell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty()) {
            char c = v.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t') {
                v = "'" + v;
            }
        }
        boolean needsQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needsQuote) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
