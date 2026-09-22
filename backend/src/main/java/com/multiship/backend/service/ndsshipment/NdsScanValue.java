package com.multiship.backend.service.ndsshipment;

/**
 * Parsed shape of a scanned NDS value like {@code .X12345} or
 * {@code .YDES875-1732899100-9030}. Produced by
 * {@link NdsScanValueParser#parse(String)}; consumed by
 * {@link NdsShipmentLookupService}.
 *
 * @param scope       DIRECT (.X = one order) or BATCH (.Y = billable batch)
 * @param stripped    the value AFTER the prefix (container_id for DIRECT,
 *                    batch_id for BATCH); trimmed + uppercased
 * @param scannedRaw  the original scanned string, for display/logging
 *                    (preserved verbatim so operators recognise their scan)
 */
public record NdsScanValue(Scope scope, String stripped, String scannedRaw) {

    /** DIRECT = .X<container_id> — one order. BATCH = .Y<batch_id> — cross-order. */
    public enum Scope { DIRECT, BATCH }
}
