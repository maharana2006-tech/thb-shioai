package com.multiship.backend.service.carriers.exceptions;

/**
 * HTTP 5xx from the carrier — carrier-side failure. Not the caller's
 * fault. Downstream mapping surfaces this as HTTP 502 Bad Gateway to
 * the API caller so ops sees "carrier is broken" not "our bug".
 */
public class CarrierServerException extends CarrierException {

    private final int carrierStatusCode;

    public CarrierServerException(String carrierCode, int carrierStatusCode, String message) {
        super(carrierCode, message);
        this.carrierStatusCode = carrierStatusCode;
    }

    public int getCarrierStatusCode() {
        return carrierStatusCode;
    }
}
