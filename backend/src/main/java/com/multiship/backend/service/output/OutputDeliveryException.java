package com.multiship.backend.service.output;

/**
 * Sprint 52 — thrown by any {@link OutputDriver} when a delivery fails.
 * Wraps the underlying I/O exception so the dispatch service can surface
 * a stable {@code OUTPUT_DELIVERY_FAILED} error code to the caller while
 * still capturing the root cause for the log line.
 */
public class OutputDeliveryException extends RuntimeException {

    private final Long destinationId;
    private final DestinationType destinationType;

    public OutputDeliveryException(Long destinationId,
                                   DestinationType destinationType,
                                   String message,
                                   Throwable cause) {
        super(message, cause);
        this.destinationId = destinationId;
        this.destinationType = destinationType;
    }

    public OutputDeliveryException(Long destinationId,
                                   DestinationType destinationType,
                                   String message) {
        this(destinationId, destinationType, message, null);
    }

    public Long getDestinationId() { return destinationId; }
    public DestinationType getDestinationType() { return destinationType; }
}
