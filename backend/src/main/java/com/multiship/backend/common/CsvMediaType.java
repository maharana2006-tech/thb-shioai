package com.multiship.backend.common;

/**
 * Sprint 51 AC-L5 — a single canonical `Content-Type` string for the CSV
 * export endpoints scattered across the codebase.
 *
 * <p>Before this class:
 * <ul>
 *   <li>{@code ClientController.exportClientsCsv} / {@code CustomsProfilesController.exportCsv}
 *       used {@code "text/csv;charset=UTF-8"}.</li>
 *   <li>{@code ReportController.orders/tracking/rate-shop/billing},
 *       {@code OrderImportController.template},
 *       {@code ScheduledReportController.download} used bare {@code "text/csv"}.</li>
 * </ul>
 * Some browsers assume Latin-1 for bare {@code text/csv} which breaks
 * non-ASCII characters in exported city / customer names. Standardising
 * on the UTF-8 form fixes that everywhere; the constant makes the choice
 * grep-able so a future adjustment is a one-line change.
 */
public final class CsvMediaType {

    /**
     * Canonical CSV media type for every endpoint that serves CSV bytes.
     * The charset parameter is required — see class Javadoc for rationale.
     */
    public static final String CSV_UTF8 = "text/csv;charset=UTF-8";

    private CsvMediaType() {
        // static-only holder — no instances.
    }
}
