package com.multiship.backend.service.resolution;

import java.math.BigDecimal;

/**
 * Snapshot of a markup calculation. The three-way {@code (kind, value,
 * currency)} freeze is what gets stamped on the shipment row so a later
 * change on {@code client_billing_markup} does not shift historical bills.
 *
 * <p>Rounding: {@code billable} is rounded HALF_UP to 4 decimal places once
 * per shipment (the DECIMAL(12,4) column) — per the Phase-1 open decision
 * "round-per-shipment stored as DECIMAL(12,4)".
 *
 * @param carrierRate what the carrier billed us (input).
 * @param billable    carrier rate + markup, rounded per above.
 * @param kind        PERCENT | FLAT — same values as
 *                    {@link com.multiship.backend.model.ClientBillingMarkup}.
 * @param value       the markup value that was applied.
 * @param currency    ISO-4217; caller ensures markup + carrier currencies
 *                    line up before invoking.
 */
public record MarkupApplied(
        BigDecimal carrierRate,
        BigDecimal billable,
        String kind,
        BigDecimal value,
        String currency
) {
}
