package com.multiship.backend.service.output;

/**
 * Sprint 52 — bag of per-dispatch context passed to every
 * {@link OutputDriver}. Kept as a record so drivers can log structured
 * fields (shipment id, order no, client code) alongside their own
 * destination-specific detail without every driver signature having
 * to grow when we want to log something new.
 *
 * <p>Any field may be null — a legacy single-shipment flow may only
 * know the orderNo, and a test dispatch (admin "test" button) has no
 * shipment context at all.
 */
public record DispatchContext(
        Long shipmentId,
        Integer orderNo,
        String clientCode,
        String contentType,
        String fileNameHint
) {

    /** Convenience — no shipment context (admin test button). */
    public static DispatchContext forTest(String clientCode) {
        return new DispatchContext(null, null, clientCode, "text/plain", "test.txt");
    }
}
