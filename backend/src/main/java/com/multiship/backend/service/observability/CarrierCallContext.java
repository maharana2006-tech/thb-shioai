package com.multiship.backend.service.observability;

import org.slf4j.MDC;

/**
 * V114 follow-up — tag per-order carrier calls so the {@code carrier_api_log}
 * row populates its {@code order_no} / {@code tracking} columns.
 *
 * <p>Usage:
 * <pre>
 *   try (var ignored = CarrierCallContext.forOrder(orderNo, trackingOrNull)) {
 *       connector.generateLabel(req, token, env);
 *   }
 * </pre>
 *
 * <p>The {@link CarrierApiLoggingInterceptor} reads these MDC keys for every
 * HTTP round-trip made inside the block. Callers that don't tag still log
 * (just without the order linkage).
 *
 * <p>Thread-local via SLF4J MDC — safe to use across non-async code. For
 * async hops (CompletableFuture, @Async, ExecutorService), the context
 * doesn't propagate; wrap the carrier call itself, not the enqueue site.
 */
public final class CarrierCallContext implements AutoCloseable {

    /** MDC key for the order ID in scope of a carrier HTTP call. */
    public static final String MDC_CARRIER_ORDER_NO = "carrierCallOrderNo";
    /** MDC key for the tracking number in scope of a carrier HTTP call. */
    public static final String MDC_CARRIER_TRACKING = "carrierCallTracking";

    private final String priorOrderNo;
    private final String priorTracking;

    private CarrierCallContext(String priorOrderNo, String priorTracking) {
        this.priorOrderNo = priorOrderNo;
        this.priorTracking = priorTracking;
    }

    /**
     * Enter a carrier-call block tagged with the given order + optional
     * tracking. Returns an AutoCloseable so callers can use try-with-
     * resources; close() restores any prior MDC values (nested blocks safe).
     */
    public static CarrierCallContext forOrder(Long orderNo, String tracking) {
        String priorOrderNo = MDC.get(MDC_CARRIER_ORDER_NO);
        String priorTracking = MDC.get(MDC_CARRIER_TRACKING);
        if (orderNo != null) {
            MDC.put(MDC_CARRIER_ORDER_NO, String.valueOf(orderNo));
        }
        if (tracking != null && !tracking.isBlank()) {
            MDC.put(MDC_CARRIER_TRACKING, tracking);
        }
        return new CarrierCallContext(priorOrderNo, priorTracking);
    }

    /** Overload for order-only tagging (tracking fills in later for ack writes). */
    public static CarrierCallContext forOrder(Long orderNo) {
        return forOrder(orderNo, null);
    }

    @Override
    public void close() {
        if (priorOrderNo == null) {
            MDC.remove(MDC_CARRIER_ORDER_NO);
        } else {
            MDC.put(MDC_CARRIER_ORDER_NO, priorOrderNo);
        }
        if (priorTracking == null) {
            MDC.remove(MDC_CARRIER_TRACKING);
        } else {
            MDC.put(MDC_CARRIER_TRACKING, priorTracking);
        }
    }
}
