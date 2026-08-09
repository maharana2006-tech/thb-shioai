package com.multiship.backend.service.carriers.exceptions;

/**
 * HTTP 401 / 403 from the carrier — token expired, revoked, or lacks
 * the required scope. Sprint 49 Tier 3's OAuth 401→refresh retry
 * catches this specifically to trigger a single refresh + retry cycle.
 */
public class CarrierAuthException extends CarrierException {

    public CarrierAuthException(String carrierCode, String message) {
        super(carrierCode, message);
    }

    public CarrierAuthException(String carrierCode, String message, Throwable cause) {
        super(carrierCode, message, cause);
    }
}
