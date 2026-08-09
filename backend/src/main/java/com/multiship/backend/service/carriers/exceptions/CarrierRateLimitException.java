package com.multiship.backend.service.carriers.exceptions;

/**
 * HTTP 429 from the carrier — throttled. Callers should back off;
 * downstream mapping surfaces this as an HTTP 429 to the API caller
 * with the {@code Retry-After} header populated from
 * {@link #retryAfterSeconds}.
 */
public class CarrierRateLimitException extends CarrierException {

    private final Integer retryAfterSeconds;

    public CarrierRateLimitException(String carrierCode, String message, Integer retryAfterSeconds) {
        super(carrierCode, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
