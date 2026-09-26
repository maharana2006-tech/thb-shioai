package com.multiship.backend.service.externalsystems.writeback;

/**
 * V89 — return shape for {@code writeShipment} / {@code clearShipment}.
 * Fire-and-forget dispatcher logs at INFO on OK, WARN on FAILED /
 * SKIPPED — never propagates the ack to the label caller.
 */
public record WritebackAck(Status status, String detail) {
    public enum Status { OK, SKIPPED, FAILED }

    public static WritebackAck ok(String detail) { return new WritebackAck(Status.OK, detail); }
    public static WritebackAck skipped(String detail) { return new WritebackAck(Status.SKIPPED, detail); }
    public static WritebackAck failed(String detail) { return new WritebackAck(Status.FAILED, detail); }
}
