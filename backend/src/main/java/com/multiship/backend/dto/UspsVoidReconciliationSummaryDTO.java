package com.multiship.backend.dto;

import java.util.List;

/**
 * PR-D — summary returned by
 * {@code POST /api/v1/admin/usps-direct/void-reconciliation/run}.
 * Reports how many rows the reconciliation service processed and how
 * many were of each outcome so the platform admin can spot-check the
 * eVS Refund report before considering the batch closed.
 *
 * <p>Bucket semantics:
 * <ul>
 *   <li>{@code processedRows} — total rows read from the uploaded CSV
 *       (header excluded).</li>
 *   <li>{@code reconciledApproved} — rows where USPS refunded the
 *       postage and the local OrderTracking row was marked
 *       {@code RECONCILED_APPROVED} (status stays {@code VOIDED}).</li>
 *   <li>{@code reconciledDenied} — rows where USPS refused the refund
 *       and the local status was flipped to {@code VOID_FAILED} with
 *       {@code RECONCILED_DENIED} reason.</li>
 *   <li>{@code pending} — rows USPS marked PENDING (or an unknown
 *       status); local state left untouched, will be retried next run.</li>
 *   <li>{@code skipped} — rows whose tracking number wasn't found in
 *       the local database, or whose local status wasn't {@code VOIDED}
 *       so the reconciliation couldn't apply.</li>
 *   <li>{@code errors} — human-readable notes about malformed rows.</li>
 * </ul>
 */
public record UspsVoidReconciliationSummaryDTO(
        int processedRows,
        int reconciledApproved,
        int reconciledDenied,
        int pending,
        int skipped,
        List<String> errors
) {
    /** Convenience for empty-input runs. */
    public static UspsVoidReconciliationSummaryDTO empty() {
        return new UspsVoidReconciliationSummaryDTO(0, 0, 0, 0, 0, List.of());
    }
}
