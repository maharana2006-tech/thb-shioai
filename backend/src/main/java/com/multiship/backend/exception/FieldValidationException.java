package com.multiship.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * A request value the server refuses, named by the field the form should
 * mark. {@code GlobalExceptionHandler} turns it into the {@code errors.field}
 * body the forms render under the input (QA report rule: every 4xx names
 * its field). Default status 422; pass 409 for a uniqueness clash.
 */
public class FieldValidationException extends RuntimeException {

    private final String field;
    private final HttpStatus status;

    public FieldValidationException(String field, String message) {
        this(field, message, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    public FieldValidationException(String field, String message, HttpStatus status) {
        super(message);
        this.field = field;
        this.status = status;
    }

    public String getField() {
        return field;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
