package com.multiship.backend.service.carriers.exceptions;

/**
 * Sprint 49 Tier 2 — base for typed carrier failures.
 *
 * <p>Previously every connector caught {@code Exception} and returned
 * a fake tracking number ({@code "1Z"+hash}, {@code "78"+digits},
 * {@code "9"+hash}) so the caller treated the call as successful. The
 * customer got charged, no real parcel existed, and no one saw the
 * error until support opened a ticket days later.
 *
 * <p>Every {@code createShipment} now throws a {@link CarrierException}
 * subclass on failure. {@code CarrierServiceImpl} maps the type to an
 * HTTP status and a {@code FAILED_CARRIER} row on the label batch —
 * the frontend surfaces a clear error rather than a phantom label.
 */
public class CarrierException extends RuntimeException {

    private final String carrierCode;

    public CarrierException(String carrierCode, String message) {
        super(message);
        this.carrierCode = carrierCode;
    }

    public CarrierException(String carrierCode, String message, Throwable cause) {
        super(message, cause);
        this.carrierCode = carrierCode;
    }

    public String getCarrierCode() {
        return carrierCode;
    }
}
