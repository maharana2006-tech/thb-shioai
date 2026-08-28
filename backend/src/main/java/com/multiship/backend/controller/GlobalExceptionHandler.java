package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API-wide bean-validation handling. Without this, a @Valid failure on any
 * controller not covered by a scoped advice fell through to the /error
 * dispatch, which Spring Security answered with a bogus 401 — and the
 * frontend force-logs-out on 401. Validation failures must always be an
 * honest 400 VALIDATION_ERROR. (CarrierExceptionHandler keeps its
 * carrier-scoped handlers; same response shape, so overlap is harmless.)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        FieldError firstFieldError = fieldErrors.isEmpty() ? null : fieldErrors.get(0);
        ApiResponse.ErrorDetails errorDetails = firstFieldError == null
                ? ApiResponse.ErrorDetails.builder()
                .field("request")
                .code("VALIDATION_ERROR")
                .message("Invalid request payload.")
                .build()
                : ApiResponse.ErrorDetails.builder()
                .field(firstFieldError.getField())
                .code(firstFieldError.getCode())
                .message(firstFieldError.getDefaultMessage())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .status("error")
                        .code(HttpStatus.BAD_REQUEST.value())
                        .errorCode(ErrorCode.VALIDATION_ERROR.name())
                        .message(buildFriendlyValidationSummary(fieldErrors))
                        .timestamp(LocalDateTime.now())
                        .errors(errorDetails)
                        .build());
    }

    /**
     * Sprint 51 polish — build a human-readable top-level {@code message} from
     * every field error the request produced. Previously hardcoded to
     * "Validation failed.", which forced operators to inspect the payload's
     * {@code errors[]} field (or DevTools) to find out which field actually
     * failed. Now:
     *
     * <ul>
     *   <li>Zero fields (unusual — bean validation without a field target):
     *       "Invalid request payload."</li>
     *   <li>One field: "Recipient phone must not be blank."</li>
     *   <li>Multiple fields: "Recipient phone must not be blank (and 2 more)."</li>
     * </ul>
     *
     * <p>Field names are humanised on a best-effort basis — nested Bean
     * Validation paths like {@code shipment.recipientPhone} become
     * "Recipient phone". Non-standard paths pass through unchanged so the
     * hint still points at the right JSON key.
     */
    static String buildFriendlyValidationSummary(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return "Invalid request payload.";
        }
        FieldError first = fieldErrors.get(0);
        String head = humaniseFieldName(first.getField()) + " "
                + defaultMessageOrGeneric(first.getDefaultMessage()) + ".";
        int more = fieldErrors.size() - 1;
        return more == 0 ? head : head + " (and " + more + " more.)";
    }

    private static String defaultMessageOrGeneric(String message) {
        return (message == null || message.isBlank()) ? "is invalid" : message;
    }

    /**
     * Turn a bean-validation path (dot-separated camelCase) into title-case
     * human text. {@code shipment.recipientPhone} → "Recipient phone".
     * {@code items[3].hsCode} → "Items[3] hs code" (list indexing preserved
     * so the operator can find the offending row).
     */
    static String humaniseFieldName(String field) {
        if (field == null || field.isBlank()) return "Field";
        String[] parts = field.split("\\.");
        String leaf = parts[parts.length - 1];
        // Split camelCase → space-separated lowercase words.
        String words = leaf.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        // Capitalise only the first letter so downstream sentence flow reads
        // naturally: "Recipient phone must not be blank." not
        // "Recipient Phone Must Not Be Blank."
        if (words.isEmpty()) return leaf;
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
