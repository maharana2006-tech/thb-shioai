package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.exception.CarrierConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 53 /settings/carriers page-tests (slice: BE-exception-handler).
 *
 * <p>{@link CarrierExceptionHandler} is a controller-advice bound
 * exclusively to {@link CarrierController} via
 * {@code assignableTypes = CarrierController.class}. It maps three
 * exception families to structured {@link ApiResponse} payloads:
 *
 * <ol>
 *   <li>{@link CarrierConnectionException} → 400 with
 *       {@link ErrorCode#CARRIER_CONNECTION_FAILED} + original message.</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 with
 *       {@link ErrorCode#VALIDATION_ERROR}, field-level details when
 *       available, generic fallback when the binding result has no
 *       field-error (defensive path).</li>
 *   <li>Any other {@link Exception} → 500 with
 *       {@link ErrorCode#INTERNAL_ERROR} + "Unexpected carrier error:"
 *       prefix. SPECIAL CASE: {@link AccessDeniedException} MUST be
 *       re-thrown so Spring Security's translation filter can produce
 *       the 403 — the handler must not mask an auth failure as a
 *       carrier 500.</li>
 * </ol>
 */
class CarrierExceptionHandlerTest {

    private CarrierExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CarrierExceptionHandler();
    }

    // ==================================================================
    // handleCarrierConnectionException
    // ==================================================================

    @Test
    void carrierConnectionException_maps_to400_withCarrierConnectionFailedErrorCode() {
        CarrierConnectionException ex = new CarrierConnectionException("carrier api rejected credentials");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleCarrierConnectionException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals("error", body.getStatus());
        assertEquals(400, body.getCode());
        assertEquals(ErrorCode.CARRIER_CONNECTION_FAILED.name(), body.getErrorCode());
        assertEquals("carrier api rejected credentials", body.getMessage());
        assertNotNull(body.getTimestamp(), "timestamp must be set for observability");
    }

    @Test
    void carrierConnectionException_nullMessage_stillProduces400_withNullMessage() {
        // Defensive: some carrier connectors throw with null messages;
        // the handler must not NPE — the message just becomes null on
        // the wire (Jackson emits explicit null).
        CarrierConnectionException ex = new CarrierConnectionException((String) null);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleCarrierConnectionException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNull(resp.getBody().getMessage());
    }

    // ==================================================================
    // handleValidationException (@Valid failure)
    // ==================================================================

    @Test
    void validationException_withFieldError_populatesFieldLevelDetails() {
        FieldError fe = new FieldError("carrierConnectRequest",
                "carrierCode", null, false, new String[]{"NotBlank"}, null,
                "must not be blank");
        BindingResult binding = mock(BindingResult.class);
        // Sprint 51 polish — handler now iterates getFieldErrors() to
        // build the friendly summary; single-field lookups also flow
        // through this list.
        when(binding.getFieldErrors()).thenReturn(java.util.List.of(fe));
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException((org.springframework.core.MethodParameter) null, binding);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), body.getErrorCode());
        // Sprint 51 polish — top-level message is humanised from the
        // first field error instead of the constant "Validation failed."
        assertEquals("Carrier code must not be blank.", body.getMessage());
        ApiResponse.ErrorDetails details = body.getErrors();
        assertNotNull(details, "field-level details must be included when a FieldError is present");
        assertEquals("carrierCode", details.getField());
        assertEquals("NotBlank", details.getCode());
        assertEquals("must not be blank", details.getMessage());
    }

    @Test
    void validationException_withoutFieldError_fallsBackToGenericRequestField() {
        // No field-error resolvable (e.g. class-level constraint) — the
        // handler uses the "request" fallback so the client still gets
        // a structured payload instead of a null body.
        BindingResult binding = mock(BindingResult.class);
        when(binding.getFieldErrors()).thenReturn(java.util.List.of());
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException((org.springframework.core.MethodParameter) null, binding);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        ApiResponse.ErrorDetails details = resp.getBody().getErrors();
        assertNotNull(details);
        assertEquals("request", details.getField());
        assertEquals("VALIDATION_ERROR", details.getCode());
        assertEquals("Invalid request payload.", details.getMessage());
        // Sprint 51 polish — the empty-field-list branch also produces
        // a friendly top-level message rather than the generic constant.
        assertEquals("Invalid request payload.", resp.getBody().getMessage());
    }

    @Test
    void validationException_multipleFieldErrors_summarisesFirstAndCountsTheRest() {
        // Sprint 51 polish — replaces the pre-polish test that pinned
        // the constant "Validation failed.". Multiple field errors now
        // roll up into "First field message. (and N more.)" so the
        // operator sees the leading cause + a hint that more work is
        // required.
        FieldError first = new FieldError("carrierConnectRequest", "clientId", "must not be blank");
        FieldError second = new FieldError("carrierConnectRequest", "clientSecret", "must not be blank");
        FieldError third = new FieldError("carrierConnectRequest", "accountNumber", "must not be blank");
        BindingResult binding = mock(BindingResult.class);
        when(binding.getFieldErrors()).thenReturn(java.util.List.of(first, second, third));
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException((org.springframework.core.MethodParameter) null, binding);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleValidationException(ex);

        assertEquals("Client id must not be blank. (and 2 more.)", resp.getBody().getMessage());
        // The single-item errors object still reflects the FIRST field
        // (backwards-compat with pre-Sprint-51 consumers that only
        // read errors.field / errors.message).
        assertEquals("clientId", resp.getBody().getErrors().getField());
    }

    // ==================================================================
    // handleGenericException — 500 + INTERNAL_ERROR; AccessDenied re-thrown
    // ==================================================================

    @Test
    void genericException_mapsTo500_withInternalErrorCode_andPrefixedMessage() throws Exception {
        RuntimeException ex = new RuntimeException("db connection lost");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleGenericException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.INTERNAL_ERROR.name(), body.getErrorCode());
        assertEquals(500, body.getCode());
        assertTrue(body.getMessage().startsWith("Unexpected carrier error:"),
                "generic-exception message must be prefixed for log-search discoverability");
        assertTrue(body.getMessage().contains("db connection lost"),
                "generic-exception message must include the original cause text");
    }

    @Test
    void genericException_withNullMessage_returnsPrefixedNullString_notCrash() throws Exception {
        // Defensive: some code paths throw with null messages; the
        // "Unexpected carrier error: " + null concatenation must survive.
        RuntimeException ex = new RuntimeException((String) null);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleGenericException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("Unexpected carrier error: null", resp.getBody().getMessage());
    }

    @Test
    void accessDeniedException_isReThrown_notMaskedAsCarrier500() {
        // CRITICAL: the handler MUST let AccessDeniedException propagate
        // so Spring Security's translation filter produces the 403.
        // Masking it as a 500 would hide auth failures.
        AccessDeniedException denied = new AccessDeniedException("no");

        AccessDeniedException thrown = assertThrows(AccessDeniedException.class,
                () -> handler.handleGenericException(denied));
        assertEquals("no", thrown.getMessage());
    }

    @Test
    void accessDeniedSubclass_isAlsoReThrown() {
        // The check is `instanceof` — any subclass of AccessDeniedException
        // (custom subclass, or wrapper types added in future Spring versions)
        // must ALSO get re-thrown.
        class MyDenied extends AccessDeniedException {
            MyDenied() { super("sub"); }
        }
        AccessDeniedException thrown = assertThrows(AccessDeniedException.class,
                () -> handler.handleGenericException(new MyDenied()));
        assertEquals("sub", thrown.getMessage());
    }

    // ==================================================================
    // Timestamps are present on ALL three code paths (observability)
    // ==================================================================

    @Test
    void allThreeHandlers_populateTimestamp() throws Exception {
        LocalDateTime before = LocalDateTime.now();

        ResponseEntity<ApiResponse<Void>> a = handler.handleCarrierConnectionException(
                new CarrierConnectionException("x"));

        BindingResult binding = mock(BindingResult.class);
        when(binding.getFieldError()).thenReturn(null);
        ResponseEntity<ApiResponse<Void>> b = handler.handleValidationException(
                new MethodArgumentNotValidException((org.springframework.core.MethodParameter) null, binding));

        ResponseEntity<ApiResponse<Void>> c = handler.handleGenericException(
                new RuntimeException("y"));

        assertNotNull(a.getBody().getTimestamp());
        assertNotNull(b.getBody().getTimestamp());
        assertNotNull(c.getBody().getTimestamp());

        assertTrue(!a.getBody().getTimestamp().isBefore(before));
        assertTrue(!b.getBody().getTimestamp().isBefore(before));
        assertTrue(!c.getBody().getTimestamp().isBefore(before));
    }

    // ==================================================================
    // Advice binding — this handler applies ONLY to CarrierController.
    // Pin the assignableTypes so a refactor can't silently widen the scope.
    // ==================================================================

    @Test
    void advice_isScopedToCarrierControllerOnly() {
        RestControllerAdvice advice = CarrierExceptionHandler.class.getAnnotation(RestControllerAdvice.class);
        assertNotNull(advice, "class must remain a @RestControllerAdvice");
        Class<?>[] types = advice.assignableTypes();
        assertEquals(1, types.length,
                "advice must remain scoped to exactly one controller");
        assertEquals(CarrierController.class, types[0]);
    }
}
