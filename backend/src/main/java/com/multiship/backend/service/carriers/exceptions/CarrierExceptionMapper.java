package com.multiship.backend.service.carriers.exceptions;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sprint 49 Tier 2 — turns Spring's opaque HTTP failures into the
 * typed hierarchy the service layer switches on.
 *
 * <p>Only used from carrier connectors, all of which now propagate
 * failures instead of returning fake tracking numbers.
 */
public final class CarrierExceptionMapper {

    private CarrierExceptionMapper() {}

    public static CarrierException map(String carrierCode, Throwable ex, String operation) {
        // Timeout / connection refused — Spring wraps IOException in ResourceAccessException.
        if (ex instanceof ResourceAccessException) {
            return new CarrierTimeoutException(carrierCode,
                    carrierCode + " " + operation + " timed out or connection refused: " + ex.getMessage(),
                    ex);
        }
        if (ex instanceof RestClientResponseException resp) {
            int status = resp.getStatusCode().value();
            String body = resp.getResponseBodyAsString();
            String msg = carrierCode + " " + operation + " HTTP " + status
                    + (body != null && !body.isEmpty() ? ": " + trimBody(body) : "");
            if (status == 401 || status == 403) {
                return new CarrierAuthException(carrierCode, msg);
            }
            if (status == 429) {
                Integer retryAfter = parseRetryAfter(resp);
                return new CarrierRateLimitException(carrierCode, msg, retryAfter);
            }
            if (status >= 400 && status < 500) {
                return new CarrierValidationException(carrierCode, msg, body);
            }
            if (status >= 500) {
                return new CarrierServerException(carrierCode, status, msg);
            }
        }
        return new CarrierException(carrierCode,
                carrierCode + " " + operation + " failed: " + ex.getMessage(), ex);
    }

    private static Integer parseRetryAfter(RestClientResponseException resp) {
        var headers = resp.getResponseHeaders();
        if (headers == null) return null;
        String v = headers.getFirst("Retry-After");
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException nfe) {
            return null;  // Retry-After can also be an HTTP date; skip for now
        }
    }

    private static String trimBody(String body) {
        if (body.length() <= 300) return body;
        return body.substring(0, 300) + "...(truncated)";
    }
}
