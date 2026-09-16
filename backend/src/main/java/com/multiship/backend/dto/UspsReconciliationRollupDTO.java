package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PR-F4 - rollup of the USPS_DIRECT void-reconciliation state for the
 * admin dashboard. Aggregates the {@code void_reconciliation_status}
 * column on {@code order_label_tracking} over a configurable lookback
 * window (default 30 days).
 *
 * <p>Answers three operational questions at a glance:
 * <ul>
 *   <li>"How many optimistic voids have we filed this month?"
 *       -&gt; {@link #voidedShipmentsInWindow}.</li>
 *   <li>"Did USPS refund them?" -&gt; the three reconciliation
 *       counters ({@link #reconciledApproved} / {@link #reconciledDenied}
 *       / {@link #notYetReconciled}).</li>
 *   <li>"What refund value is still hanging?" -&gt;
 *       {@link #pendingRefundValue} (best-effort sum of
 *       {@code billable_amount} for {@code voidReconciliationStatus IS
 *       NULL} rows).</li>
 * </ul>
 *
 * <p>Empty-DB / no-void case: every counter returns 0,
 * {@link #lastReconciliationAt} is {@code null}, and
 * {@link #pendingRefundValue} is {@link BigDecimal#ZERO}. Callers can
 * render "no voids in the last N days" without special-casing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UspsReconciliationRollupDTO {

    /** Rolling-window size, in days, the counters cover. */
    private int lookbackDays;

    /** Total USPS shipments whose status flipped to VOIDED in the window. */
    private long voidedShipmentsInWindow;

    /** Count where USPS refunded the postage (status stayed VOIDED). */
    private long reconciledApproved;

    /** Count where USPS refused the refund (status flipped to VOID_FAILED). */
    private long reconciledDenied;

    /** Count still awaiting an eVS Refund report upload. */
    private long notYetReconciled;

    /**
     * Most recent {@code void_reconciliation_checked_at} timestamp
     * across all rows in the window. {@code null} when no reconciliation
     * has ever run in the window.
     */
    private LocalDateTime lastReconciliationAt;

    /**
     * Best-effort sum of {@code billable_amount} for rows with
     * {@code void_reconciliation_status IS NULL} - the outstanding
     * refund value the platform is still waiting on USPS to
     * acknowledge. Zero when no rows populated the column (freshly
     * queued voids may not have carrier_amount / billable_amount yet).
     */
    private BigDecimal pendingRefundValue;

    /** ISO-4217 currency of {@link #pendingRefundValue}. Best-effort - USPS
     *  Direct is USD-only in practice, so this defaults to {@code "USD"}. */
    private String currency;
}
