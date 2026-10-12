package com.multiship.backend.exception;

import com.multiship.backend.dto.ErrorCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * A deliberate HTTP error that also carries a machine-readable {@link ErrorCode},
 * so the frontend can branch on the cause rather than the bare status. Rendered
 * by {@code GlobalExceptionHandler} like any {@link ResponseStatusException}, but
 * with {@code errorCode} set from {@link #getErrorCode()} instead of the HTTP name.
 */
public class CodedStatusException extends ResponseStatusException {

    private final transient ErrorCode errorCode;

    public CodedStatusException(HttpStatusCode status, ErrorCode errorCode, String reason) {
        super(status, reason);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
