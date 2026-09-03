package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API-wide bean-validation handling. Without this, a @Valid failure on any
 * controller not covered by a scoped advice fell through to the /error
 * dispatch, which Spring Security answered with a bogus 401 — and the
 * frontend force-logs-out on 401. Validation failures must always be an
 * honest 400 VALIDATION_ERROR. (CarrierExceptionHandler keeps its
 * carrier-scoped handlers; same response shape, so overlap is harmless.)
 */
@Slf4j
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

    // ─── catch-all: unexpected runtime errors ───────────────────────

    /**
     * PR #554 — catch-all for unexpected {@link RuntimeException} that
     * no other handler owns. Prevents raw Java exception messages
     * (class names, method signatures, stack fragments) from leaking
     * into the user-facing response body. Users see a friendly
     * "something went wrong" line + a correlation ID; the full
     * exception details are logged server-side WARN with the same ID
     * so ops can trace the incident.
     *
     * <p>Motivating incident: a Lombok {@code $default$masterTrackings()}
     * {@link NoSuchMethodError} from a stale-hot-reload of
     * TrackingResponseDTO surfaced verbatim in the UI toast — the raw
     * method signature confused the operator + leaked internals.
     *
     * <p>Scope: any {@link RuntimeException} NOT caught by a more
     * specific handler (validation, carrier, order, etc.) lands here.
     * Existing narrower handlers keep precedence.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedRuntime(RuntimeException ex) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.warn("Unexpected server error [ref={}]: {}", correlationId, ex.toString(), ex);
        return respondFriendly(correlationId, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * PR #554 — {@link Error} subclasses ({@link NoSuchMethodError},
     * {@link IncompatibleClassChangeError}, {@link LinkageError} etc.)
     * are NOT caught by {@code @ExceptionHandler(Exception.class)} or
     * {@code (RuntimeException.class)} because they don't extend
     * Exception. We DO want to catch the LinkageError family — these
     * fire on class-file drift after a hot-reload (exact symptom of
     * the {@code $default$masterTrackings()} incident) and would
     * otherwise leak the raw JVM message.
     *
     * <p>NOT catching {@link OutOfMemoryError} / {@link StackOverflowError}
     * — those are genuinely fatal for the JVM; letting Spring's default
     * path handle them is safer than trying to build a response body
     * from a dead heap.
     */
    @ExceptionHandler(LinkageError.class)
    public ResponseEntity<ApiResponse<Void>> handleLinkageError(LinkageError err) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.warn("Class-linkage error [ref={}] — usually stale class file after hot-reload; a full backend restart typically fixes this. {}",
                correlationId, err.toString(), err);
        return respondFriendly(correlationId, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** Shared friendly-response builder for both catch-alls above. */
    private static ResponseEntity<ApiResponse<Void>> respondFriendly(String correlationId, HttpStatus status) {
        return ResponseEntity.status(status)
                .body(ApiResponse.<Void>builder()
                        .status("error")
                        .code(status.value())
                        .errorCode(ErrorCode.INTERNAL_ERROR.name())
                        .message("Something went wrong on the server. Please try again in a moment. "
                                + "If this keeps happening, contact support with reference "
                                + correlationId + ".")
                        .timestamp(LocalDateTime.now())
                        .errors(ApiResponse.ErrorDetails.builder()
                                .field("request")
                                .code("INTERNAL_ERROR")
                                .message("Reference " + correlationId + " — details in server logs.")
                                .build())
                        .build());
    }
}
