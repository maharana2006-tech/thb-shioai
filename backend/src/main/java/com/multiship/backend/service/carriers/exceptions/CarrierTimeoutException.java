package com.multiship.backend.service.carriers.exceptions;

/**
 * The carrier didn't respond within the configured connect + read
 * timeout (see {@link com.multiship.backend.service.carriers.HttpClients}).
 * Callers should treat this as a transient failure — the request may
 * or may not have reached the carrier.
 */
public class CarrierTimeoutException extends CarrierException {

    public CarrierTimeoutException(String carrierCode, String message, Throwable cause) {
        super(carrierCode, message, cause);
    }
}
