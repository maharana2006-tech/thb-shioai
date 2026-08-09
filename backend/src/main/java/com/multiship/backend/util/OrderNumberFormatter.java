package com.multiship.backend.util;

/**
 * Sprint 48 B10 — customer-facing order-number format. The DB column
 * stays INTEGER (no schema disruption), but manual shipments render with
 * a {@code MAN} prefix on the label, invoice, and UI so ops can tell at
 * a glance which orders were hand-entered vs pulled from an ERP feed.
 *
 * <p>Only manual orders get the prefix; ERP / WMS / API-imported orders
 * render just the number as before.
 */
public final class OrderNumberFormatter {

    private OrderNumberFormatter() {}

    /** True when the order was hand-entered via the New Shipment UI or
     *  the manual-shipment API endpoint (label_batch.is_manual = 'Y'). */
    public static boolean isManual(String isManualFlag) {
        return isManualFlag != null && "Y".equalsIgnoreCase(isManualFlag.trim());
    }

    /**
     * @param orderNo      the integer order number from the DB
     * @param isManualFlag {@code label_batch.is_manual} (Y/N/null)
     * @return e.g. {@code "MAN900001"} for manual orders,
     *         {@code "900001"} for ERP/WMS/API orders.
     */
    public static String format(Integer orderNo, String isManualFlag) {
        if (orderNo == null) return "";
        return isManual(isManualFlag) ? "MAN" + orderNo : String.valueOf(orderNo);
    }

    /** Convenience for when the order + suffix are both known and the
     *  caller wants the combined display (e.g. "MAN900001-2"). */
    public static String format(Integer orderNo, Integer orderSuffix, String isManualFlag) {
        String base = format(orderNo, isManualFlag);
        if (orderSuffix == null || orderSuffix == 0) return base;
        return base + "-" + orderSuffix;
    }
}
