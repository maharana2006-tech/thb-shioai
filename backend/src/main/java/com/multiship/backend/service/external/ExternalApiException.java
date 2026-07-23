package com.multiship.backend.service.external;

import com.multiship.backend.dto.ErrorCode;

/** A public-API failure carrying an HTTP status + machine-readable error code. */
public class ExternalApiException extends RuntimeException {

    private final int status;
    private final ErrorCode errorCode;

    public ExternalApiException(int status, ErrorCode errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public int getStatus() { return status; }
    public ErrorCode getErrorCode() { return errorCode; }
}
