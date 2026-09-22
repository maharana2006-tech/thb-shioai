package com.multiship.backend.service.ndsshipment;

import java.util.List;

/**
 * Post-ship writeback contract — invoked AFTER a label is generated for
 * a shipment that was prefilled from NDS, to update the Oracle side
 * (CLIPPER / OE_TRACKING / TB_MANUAL_SHIPMENT) with the tracking
 * number, freight amount, and shipment status.
 *
 * <p><b>Not implemented in PR1.</b> The {@link NoopNdsShipmentWriteback}
 * bean logs the call and returns success without touching Oracle;
 * shippers can complete the prefill → ship loop and the writeback will
 * be a separate task that plugs an Oracle-writing implementation in
 * front of this interface via {@code @Primary} or profile.
 *
 * <p>The {@link Payload} shape carries every field the future writeback
 * will need (per container, per order) so the FE / prefill service
 * doesn't have to change when writeback lands.
 */
public interface NdsShipmentWriteback {

    /**
     * @param payload the shipment data the FE surfaces + the tracking result
     *                the carrier returned. Complete enough that a writeback
     *                implementation can locate every affected CLIPPER /
     *                OE_TRACKING / TB_MANUAL_SHIPMENT row without another
     *                NDS lookup.
     * @return the ack the caller reports upstream — INFO-level logged.
     */
    Ack writeback(Payload payload);

    /**
     * Payload shape passed by the caller. Immutable value.
     *
     * @param scannedValue        the original {@code .X} / {@code .Y} value
     *                            the shipper scanned
     * @param scope               DIRECT / BATCH (mirrors {@link NdsShipmentPrefill.Scope})
     * @param clientCode          NDS TENANT_ID / FF_SCHEMA
     * @param batchId             null for DIRECT
     * @param trackingNumber      carrier tracking number (master or SCAC-encoded)
     * @param carrierCode         UPS / FEDEX / USPS / DHL / STAMPS / ...
     * @param serviceCode         carrier-side service code (e.g. FEDEX_GROUND)
     * @param freightAmount       final freight cost — may be null when unrated
     * @param currency            ISO-4217 for {@code freightAmount}
     * @param packages            one entry per FE package row (post-shipper-edits)
     */
    record Payload(
            String scannedValue,
            NdsShipmentPrefill.Scope scope,
            String clientCode,
            String batchId,
            String trackingNumber,
            String carrierCode,
            String serviceCode,
            java.math.BigDecimal freightAmount,
            String currency,
            List<PackagePayload> packages
    ) {}

    /**
     * Per-package payload shape. Retains every NDS-side key the writeback
     * needs to locate rows for update.
     */
    record PackagePayload(
            int sequence,
            String containerNo,
            /** Every CLIPPER row keyed by this container_id gets the update. */
            List<Long> containerIds,
            /** Every OE_TRACKING row keyed by this order_no gets the update
             *  (multi-order case: several orders share one physical container). */
            List<Integer> orderNos,
            Integer orderSuffix,
            java.math.BigDecimal weight,
            String weightUnit,
            /** Per-package tracking number when the carrier returns MPS
             *  child trackings; null when only the master tracking applies. */
            String packageTracking
    ) {}

    /** Return shape — status + optional plain-English detail for logs / UI. */
    record Ack(Status status, String detail) {
        public enum Status { OK, SKIPPED, FAILED }

        public static Ack ok(String detail) { return new Ack(Status.OK, detail); }
        public static Ack skipped(String detail) { return new Ack(Status.SKIPPED, detail); }
        public static Ack failed(String detail) { return new Ack(Status.FAILED, detail); }
    }
}
