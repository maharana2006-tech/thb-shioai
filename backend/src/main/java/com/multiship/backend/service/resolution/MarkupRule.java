package com.multiship.backend.service.resolution;

import com.multiship.backend.model.ClientBillingMarkup;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The markup decision for one shipment, taken before the carrier is called.
 * {@link #apply(BigDecimal)} is pure arithmetic and cannot fail, so the
 * billable amount can always be computed after the purchase. (Phase 1 of
 * the Manual Shipment redesign: nothing after the carrier call may reject.)
 *
 * @param kind     PERCENT | FLAT
 * @param value    the markup value
 * @param currency ISO-4217 billing currency the rule is stored in
 */
public record MarkupRule(String kind, BigDecimal value, String currency) {

    public static MarkupRule passThrough(String currency) {
        return new MarkupRule(ClientBillingMarkup.KIND_PERCENT, BigDecimal.ZERO, currency);
    }

    public MarkupApplied apply(BigDecimal carrierRate) {
        BigDecimal safeRate = carrierRate != null ? carrierRate : BigDecimal.ZERO;
        BigDecimal v = value != null ? value : BigDecimal.ZERO;
        BigDecimal billable = ClientBillingMarkup.KIND_FLAT.equalsIgnoreCase(kind)
                ? safeRate.add(v)
                : safeRate.multiply(BigDecimal.ONE.add(v.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
        return new MarkupApplied(round(safeRate), round(billable), kind, v, currency);
    }

    private static BigDecimal round(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }
}
