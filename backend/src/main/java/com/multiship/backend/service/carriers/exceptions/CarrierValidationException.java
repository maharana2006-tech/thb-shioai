package com.multiship.backend.service.carriers.exceptions;

/**
 * HTTP 400 / 422 from the carrier — the request payload was rejected
 * (bad address, invalid service code, weight over max, etc.). Not
 * retryable; the customer needs to fix the request.
 */
public class CarrierValidationException extends CarrierException {

    private final String carrierErrorBody;

    public CarrierValidationException(String carrierCode, String message, String carrierErrorBody) {
        super(carrierCode, message);
        this.carrierErrorBody = carrierErrorBody;
    }

    public String getCarrierErrorBody() {
        return carrierErrorBody;
    }
}
