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
                        .status("ERROR")
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
    /** Webhooks / stored secrets need SECRETS_ENCRYPTION_KEY: say so instead of a generic 500. */
    @ExceptionHandler(com.multiship.backend.config.CryptoUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleCryptoUnavailable(com.multiship.backend.config.CryptoUnavailableException ex) {
        log.warn("Secrets encryption unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.<Void>builder()
                .status("ERROR").code(503)
                .message("This feature stores a secret and the server has no SECRETS_ENCRYPTION_KEY configured. "
                        + "Set the environment variable (base64 32-byte AES-256 key) and restart the backend.")
                .errorCode(ErrorCode.INTERNAL_ERROR.name())
                .build());
    }

    /** Import-history state guards (in Trash, generating, nothing to generate, …) — their own status + message. */
    @ExceptionHandler(com.multiship.backend.service.OrderImportServiceImpl.ImportBatchStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleImportBatchState(
            com.multiship.backend.service.OrderImportServiceImpl.ImportBatchStateException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.<Void>builder()
                .status("ERROR").code(ex.getStatus())
                .message(ex.getMessage())
                .errorCode(ex.getErrorCode() != null ? ex.getErrorCode() : ErrorCode.IMPORT_BATCH_STATE.name())
                .build());
    }

    /**
     * BUG-063 — a path variable or query parameter that doesn't convert
     * to its declared type (e.g. {@code GET /api/v1/orders/abc} when
     * {@code orderNo} is Integer) is the caller's mistake, not a server
     * fault. Pre-fix the catch-all RuntimeException handler mapped
     * {@link org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}
     * to 500 INTERNAL_ERROR; map it to 400 with the parameter name named.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.debug("Type mismatch on {}: {}", ex.getName(), ex.getMessage());
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "the expected type";
        // BUG-070 R7 — populate errors.field so the body matches the normal
        // 404/validation shape; pre-fix this handler returned `errors: null`
        // while the sibling 404 for the same route named the parameter.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.<Void>builder()
                .status("ERROR").code(400)
                .message("Parameter '" + ex.getName() + "' must be " + expected + ".")
                .errorCode(ErrorCode.MALFORMED_REQUEST.name())
                .errors(ApiResponse.ErrorDetails.builder()
                        .field(ex.getName())
                        .code(ErrorCode.MALFORMED_REQUEST.name())
                        .message("Must be " + expected + ".")
                        .build())
                .build());
    }

    /**
     * BUG-070 R7 — unknown API routes previously fell through to Spring's
     * {@code /error} dispatch, which returns the default
     * {@code {timestamp, status, error, message, path}} shape — totally
     * different from this app's {@code {status, code, errorCode, message,
     * errors, timestamp}} envelope. Catch the Spring-internal
     * {@code NoResourceFoundException} and shape it like every other 404.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.debug("No such route: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.<Void>builder()
                .status("ERROR").code(404)
                .message("No such API route.")
                .errorCode(ErrorCode.ROUTE_NOT_FOUND.name())
                .errors(ApiResponse.ErrorDetails.builder()
                        .field("path")
                        .code(ErrorCode.ROUTE_NOT_FOUND.name())
                        .message("/" + ex.getResourcePath())
                        .build())
                .build());
    }

    /**
     * BUG-077 / 083 / 098 / 099 / 113 / 118 — generic input-shape errors
     * from the data layer or bean-validation layer that services didn't
     * catch themselves. All six are 'caller gave something the storage /
     * schema can't accept' (over-long name, duplicate PK, FK violation,
     * BigDecimal overflow, @Size overflow). Map every one to 400 with
     * a cleaned-up message instead of leaking the 500 + stack trace to
     * the operator. Service-level @ControllerAdvice sites that already
     * map these more specifically still win via @Order(0) elsewhere.
     */
    @ExceptionHandler({
        org.springframework.dao.DataIntegrityViolationException.class,
        jakarta.validation.ConstraintViolationException.class,
        // BUG-098, BUG-118 — many admin controllers throw IllegalArgumentException
        // for domain validation ('holiday for 2026-12-24 already exists', 'client
        // code required'). Pre-fix those fell to the catch-all RuntimeException
        // handler → 500 'Something went wrong on the server'. IAE is a known
        // signal; map it to 400 with the message passed through verbatim.
        IllegalArgumentException.class,
    })
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(Exception ex) {
        log.debug("Data-integrity / constraint-violation surfaced to handler: {}", ex.getMessage());
        String msg = humanizeDataError(ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.<Void>builder()
                .status("ERROR").code(400)
                .message(msg)
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .build());
    }

    /** Best-effort cleanup of a jakarta.validation / Hibernate / Postgres message
     *  so the operator sees "Name must be between 0 and 255" not a 300-char
     *  Postgres backtrace. Falls back to a generic sentence when the message
     *  carries no useful content (e.g. an opaque unique-constraint name). */
    private static String humanizeDataError(Exception ex) {
        // BUG-098/118 — pass IllegalArgumentException's own message through
        // (service writes operator-facing text). DB / bean-validation
        // messages still humanize below.
        if (ex instanceof IllegalArgumentException) {
            return ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "Please check the form — one or more values are invalid."
                    : ex.getMessage();
        }
        String raw = ex.getMessage() == null ? "" : ex.getMessage();
        // Hibernate: "could not execute statement; SQL [...]; constraint ["foo_bar_unique"]"
        if (raw.contains("constraint [") || raw.contains("constraint \"")) {
            if (raw.toLowerCase().contains("unique")) {
                return "That value is already used elsewhere.";
            }
            if (raw.toLowerCase().contains("foreign key")) {
                return "This references something that doesn't exist.";
            }
        }
        // Postgres: "value too long for type character varying(255)"
        if (raw.contains("value too long")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("character varying\\((\\d+)\\)").matcher(raw);
            if (m.find()) return "A value is too long for its field (limit " + m.group(1) + " characters).";
            return "A value is too long for its field.";
        }
        if (raw.toLowerCase().contains("numeric field overflow")) {
            return "A number is too large for its field.";
        }
        // jakarta.validation ConstraintViolation: "field: must not be blank"
        if (raw.contains("ConstraintViolation") || raw.contains("must not")) {
            return "Please check the form — one or more fields are missing or invalid.";
        }
        return "This value can't be saved — check the form and try again.";
    }

    /** A missing body, or one that isn't valid JSON for the endpoint, is the caller's mistake — 400, not 500. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        boolean missing = ex.getMessage() != null && ex.getMessage().startsWith("Required request body is missing");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.<Void>builder()
                .status("ERROR").code(400)
                .message(missing
                        ? "This request needs a JSON body."
                        : "The request body isn't valid for this endpoint — check the field names and value types.")
                .errorCode(ErrorCode.MALFORMED_REQUEST.name())
                .build());
    }

    /**
     * A controller that names its own status means it — honour it.
     *
     * <p>{@code ResponseStatusException} is a RuntimeException, so the
     * catch-all below was turning every deliberate 503 / 404 / 409 into
     * "Something went wrong on the server" with a 500. Live updates refusing
     * with 503 when Redis is absent came back as a 500 that way.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        int code = ex.getStatusCode().value();
        log.debug("Deliberate {} from a controller: {}", code, ex.getReason());
        String errorCode = ex instanceof com.multiship.backend.exception.CodedStatusException coded
                && coded.getErrorCode() != null
                ? coded.getErrorCode().name() : HttpStatus.valueOf(code).name();
        return ResponseEntity.status(code).body(ApiResponse.<Void>builder()
                .status("ERROR").code(code)
                .message(ex.getReason() != null ? ex.getReason() : "Request refused.")
                .errorCode(errorCode)
                .build());
    }

    /**
     * CarrierConnectionException is a well-defined domain exception (carrier
     * disabled, credentials missing, operator-recoverable auth error). Prior
     * to this handler it was only mapped to 400 by {@code CarrierExceptionHandler}
     * which is scoped to {@code CarrierController} — so a save on
     * {@code AccountRefController} that triggered the same exception fell
     * through to {@link #handleUnexpectedRuntime} and surfaced as a generic
     * 500 "Something went wrong" with a correlation id. The user has no way
     * to recover from an opaque 500 for what is a config problem they can
     * fix in /settings/carriers. Return a 400 + the actual message instead.
     */
    @ExceptionHandler(com.multiship.backend.exception.CarrierConnectionException.class)
    public ResponseEntity<ApiResponse<Void>> handleCarrierConnection(
            com.multiship.backend.exception.CarrierConnectionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .status("ERROR")
                        .code(HttpStatus.BAD_REQUEST.value())
                        .errorCode(com.multiship.backend.dto.ErrorCode.CARRIER_CONNECTION_FAILED.name())
                        .message(ex.getMessage())
                        .build());
    }

    /**
     * Spring Security's @PreAuthorize-based method security throws
     * AuthorizationDeniedException (a RuntimeException) which, pre-fix,
     * fell to {@link #handleUnexpectedRuntime} and returned 500 with a
     * correlation id — opaque to the operator and misleading in logs
     * (the request wasn't a server bug, the caller lacked a role). Map
     * to a clean 403 FORBIDDEN with a user-facing message. Spotted
     * running a USER-role smoke test against /api/v1/orders.
     */
    @ExceptionHandler(org.springframework.security.authorization.AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(
            org.springframework.security.authorization.AuthorizationDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.<Void>builder()
                        .status("ERROR")
                        .code(HttpStatus.FORBIDDEN.value())
                        .errorCode("FORBIDDEN")
                        .message("You do not have permission to perform this action.")
                        .build());
    }

    /**
     * R3-BUG-002 — {@link org.springframework.security.access.AccessDeniedException}
     * is Spring Security's older access-denied type; service layers (e.g.
     * {@code TenantScopeEnforcer#crossTenantDenied}) throw it from inside a
     * {@code @Transactional} method so the servlet-level ExceptionTranslationFilter
     * doesn't see it. Pre-fix it fell to {@link #handleUnexpectedRuntime} and
     * came back as a 500 INTERNAL_ERROR with a correlation id, drowning the
     * logs in false-positives for routine cross-tenant request shaping (e.g.
     * a USER posting /shipments/validate with a foreign clientCode).
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        String message = ex.getMessage();
        String errorCode = "FORBIDDEN";
        // TenantScopeEnforcer tags its exceptions with "CROSS_TENANT_ACCESS_DENIED: ..."
        // so machine-readable callers can distinguish tenant isolation from
        // a plain role check.
        if (message != null && message.startsWith(com.multiship.backend.dto.ErrorCode.CROSS_TENANT_ACCESS_DENIED.name())) {
            errorCode = com.multiship.backend.dto.ErrorCode.CROSS_TENANT_ACCESS_DENIED.name();
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.<Void>builder()
                        .status("ERROR")
                        .code(HttpStatus.FORBIDDEN.value())
                        .errorCode(errorCode)
                        .message("You do not have permission to perform this action.")
                        .build());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedRuntime(RuntimeException ex) {
        // Client-disconnect detection: HttpMessageNotWritableException
        // wrapping an IOException means the browser closed the socket
        // BEFORE we finished writing the response — normal operator
        // behaviour (tab closed, page navigated, fetch timeout). We
        // can't send a response either way (socket is gone), so return
        // a minimal placeholder and DEBUG-log it. Prior behaviour of
        // logging WARN + minting a correlation ID + stack trace made
        // every closed-tab-during-a-slow-request look like a server
        // bug in the log.
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected before response completed: {}", ex.getMessage());
            // Return an empty 500 — the client is gone anyway, so the
            // body will never reach anyone. Sole purpose is to satisfy
            // Spring's contract that an @ExceptionHandler returns
            // something.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.warn("Unexpected server error [ref={}]: {}", correlationId, ex.toString(), ex);
        return respondFriendly(correlationId, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * True when the exception represents "the browser closed the
     * connection before we finished writing the response". Fires when
     * an operator closes a tab / navigates away / hits the FE fetch
     * timeout during a long-running request. Two exact shapes:
     * <ul>
     *   <li><b>Top-level</b> is
     *       {@link org.springframework.http.converter.HttpMessageNotWritableException} —
     *       Jackson message converter failed to write the response
     *       body. The ONLY realistic cause of "write failed" is the
     *       socket being gone.</li>
     *   <li><b>Any level</b> is Tomcat's
     *       {@link org.apache.catalina.connector.ClientAbortException}
     *       (typed wrapper; extends IOException).</li>
     * </ul>
     * Deliberately does NOT match on generic {@link java.io.IOException}
     * in the causal chain — a database connection failure would also
     * surface as IOException and we don't want to silence those.
     */
    private static boolean isClientDisconnect(Throwable ex) {
        if (ex instanceof org.springframework.http.converter.HttpMessageNotWritableException) {
            return true;
        }
        Throwable t = ex;
        while (t != null) {
            if ("ClientAbortException".equals(t.getClass().getSimpleName())) return true;
            t = t.getCause();
        }
        return false;
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
                        .status("ERROR")
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
