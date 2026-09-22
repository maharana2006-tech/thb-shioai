package com.multiship.backend.service.ndsshipment;

import java.util.Locale;

/**
 * Parses the scanner-produced value on the Manual Shipment page.
 *
 * <p><b>Accepted shapes:</b>
 * <ul>
 *   <li>{@code .X<containerId>} — direct order lookup (one order)</li>
 *   <li>{@code .Y<batchId>} — billable batch lookup</li>
 * </ul>
 *
 * <p><b>Tolerances</b> (all in one place so the rules live somewhere
 * predictable + testable):
 * <ul>
 *   <li>Prefix is case-insensitive: {@code .x123} and {@code .X123} both parse.</li>
 *   <li>Leading / trailing whitespace stripped.</li>
 *   <li>Trailing newline / carriage-return / tab tolerated — most USB scanners
 *       append one as a "commit" character.</li>
 *   <li>Result's {@link NdsScanValue#stripped()} is uppercased for stable
 *       downstream binds; {@code scannedRaw} preserves the original.</li>
 * </ul>
 *
 * <p><b>Rejected:</b>
 * <ul>
 *   <li>Null / blank input.</li>
 *   <li>No prefix at all ("12345", "DES875-...").</li>
 *   <li>Wrong prefix ("{@code .Z}12345", "{@code X}12345" — no leading dot).</li>
 *   <li>Prefix with empty payload ("{@code .X}" alone, "{@code .Y }").</li>
 * </ul>
 * All rejects throw {@link IllegalArgumentException} with a message the
 * controller maps to HTTP 422.
 */
public final class NdsScanValueParser {

    private NdsScanValueParser() {}

    private static final String PREFIX_DIRECT = ".X";
    private static final String PREFIX_BATCH  = ".Y";

    /**
     * @throws IllegalArgumentException on any unparseable input; message is
     *         user-safe (used in 422 body).
     */
    public static NdsScanValue parse(String scanned) {
        if (scanned == null || scanned.isBlank()) {
            throw new IllegalArgumentException("Scan must start with .X or .Y");
        }
        String raw = scanned;
        // Strip scanner-appended newline / CR / tab, then trim whitespace.
        String cleaned = scanned.replace("\r", "").replace("\n", "").replace("\t", "").trim();
        if (cleaned.length() < 3) {
            // Shortest valid input is 3 chars: "prefix + 1-char payload".
            throw new IllegalArgumentException("Scan must start with .X or .Y");
        }
        String upper = cleaned.toUpperCase(Locale.ROOT);
        NdsScanValue.Scope scope;
        if (upper.startsWith(PREFIX_DIRECT)) {
            scope = NdsScanValue.Scope.DIRECT;
        } else if (upper.startsWith(PREFIX_BATCH)) {
            scope = NdsScanValue.Scope.BATCH;
        } else {
            throw new IllegalArgumentException("Scan must start with .X or .Y");
        }
        // Slice off the 2-char prefix (preserve case in the raw string only).
        String stripped = upper.substring(2);
        if (stripped.isBlank()) {
            throw new IllegalArgumentException("Scan must include a "
                    + (scope == NdsScanValue.Scope.DIRECT ? "container id" : "batch id")
                    + " after the " + (scope == NdsScanValue.Scope.DIRECT ? ".X" : ".Y") + " prefix");
        }
        return new NdsScanValue(scope, stripped, raw);
    }
}
