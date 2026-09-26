package com.multiship.backend.service.externalsystems.writeback;

import java.util.List;
import java.util.Map;

/**
 * V89 — request shape for {@code clearShipment} on label void. The
 * connector uses these lookup keys to find the same rows the earlier
 * {@code writeShipment} touched and NULLs / VOIDs them based on the
 * connection's flag matrix.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code connectionName} — for logging</li>
 *   <li>{@code trackingNumber} — the tracking that got voided
 *       (primary lookup key for most external systems)</li>
 *   <li>{@code orderNo} — multiship order number the void targets</li>
 *   <li>{@code clientCode} — tenant / client identifier</li>
 *   <li>{@code containerIds} — NDS CLIPPER row ids from the original
 *       writeback; empty for non-WMS orders</li>
 *   <li>{@code orderNos} — NDS OE_TRACKING order_no values from the
 *       original writeback; empty for non-WMS orders</li>
 *   <li>{@code connectionSpecific} — free-form map of extra keys the
 *       connector recognises (kept for forward-compat; empty today)</li>
 * </ul>
 */
public record WritebackClearRequest(
        String connectionName,
        String trackingNumber,
        Integer orderNo,
        String clientCode,
        List<Long> containerIds,
        List<Integer> orderNos,
        Map<String, Object> connectionSpecific,
        /** V89 flag matrix — passed through so connectors that support
         *  partial clears (e.g. NdsShipmentOracleWriter's per-column
         *  SET/NULL) honour the same symmetric-flag rule as the
         *  generate side. All-true = null every column. All-false =
         *  no-op (the dispatcher won't even call). */
        boolean clearTracking,
        boolean clearShipDate,
        boolean clearStatus,
        boolean clearCarrier,
        boolean clearService,
        boolean clearFreight
) {
    /** Minimal constructor with all flags on — for simple connectors
     *  (REST) that don't do per-column selection anyway. */
    public static WritebackClearRequest of(String connectionName, String trackingNumber,
                                            Integer orderNo, String clientCode) {
        return new WritebackClearRequest(connectionName, trackingNumber, orderNo,
                clientCode, List.of(), List.of(), Map.of(),
                true, true, true, true, true, true);
    }
}
