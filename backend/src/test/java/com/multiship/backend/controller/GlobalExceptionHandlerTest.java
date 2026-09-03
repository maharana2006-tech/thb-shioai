package com.multiship.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.validation.FieldError;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprint 51 polish — {@link GlobalExceptionHandler#buildFriendlyValidationSummary}
 * builds the top-level {@code message} on a 400 VALIDATION_ERROR response.
 *
 * <p>Pre-polish the string was hardcoded to "Validation failed.", forcing
 * operators to dig into the payload's {@code errors} object (or DevTools)
 * to find which field failed. Now the summary is human-readable:
 *
 * <ul>
 *   <li>Zero fields → "Invalid request payload."</li>
 *   <li>One field → "Recipient phone must not be blank."</li>
 *   <li>Multiple fields → "Recipient phone must not be blank. (and 2 more.)"</li>
 * </ul>
 *
 * <p>Nested paths (e.g. {@code shipment.recipientPhone}) are humanised
 * on a best-effort basis so the leaf identifier drives the sentence.
 */
class GlobalExceptionHandlerTest {

    @Test
    void emptyFieldErrors_returnsGenericFallback() {
        String summary = GlobalExceptionHandler.buildFriendlyValidationSummary(List.of());
        assertEquals("Invalid request payload.", summary);
    }

    @Test
    void nullFieldErrors_returnsGenericFallback() {
        String summary = GlobalExceptionHandler.buildFriendlyValidationSummary(null);
        assertEquals("Invalid request payload.", summary);
    }

    @Test
    void singleFieldError_humanisesPathAndAppendsPeriod() {
        FieldError fe = new FieldError("shipmentRequestDTO", "shipment.recipientPhone",
                "must not be blank");
        String summary = GlobalExceptionHandler.buildFriendlyValidationSummary(List.of(fe));
        assertEquals("Recipient phone must not be blank.", summary);
    }

    @Test
    void multipleFieldErrors_summariseFirstAndCountTheRest() {
        FieldError phone = new FieldError("shipmentRequestDTO", "shipment.recipientPhone",
                "must not be blank");
        FieldError name = new FieldError("shipmentRequestDTO", "shipment.recipientName",
                "must not be blank");
        FieldError line1 = new FieldError("shipmentRequestDTO", "shipment.recipientAddressLine1",
                "must not be blank");
        String summary = GlobalExceptionHandler.buildFriendlyValidationSummary(
                List.of(phone, name, line1));
        assertEquals("Recipient phone must not be blank. (and 2 more.)", summary);
    }

    @Test
    void listIndexingPreservedInPath() {
        // Nested list indices are useful — the operator needs to know
        // which customs line item failed.
        FieldError fe = new FieldError("shipmentRequestDTO", "items[3].hsCode",
                "must match \\d{4}\\.?\\d{2}");
        String summary = GlobalExceptionHandler.buildFriendlyValidationSummary(List.of(fe));
        assertEquals("Hs code must match \\d{4}\\.?\\d{2}.", summary);
    }

    @Test
    void nullDefaultMessage_fallsBackToGenericIsInvalid() {
        FieldError fe = new FieldError("orderDTO", "customerNo", null);
        String summary = GlobalExceptionHandler.buildFriendlyValidationSummary(List.of(fe));
        assertEquals("Customer no is invalid.", summary);
    }

    @Test
    void camelCasePathSplit_producesReadableSentence() {
        // Ensure our camelCase → space-separated logic doesn't do anything
        // weird on already-lower-case paths.
        FieldError fe = new FieldError("addressDTO", "postalCode", "size must be 5-10");
        String summary = GlobalExceptionHandler.buildFriendlyValidationSummary(List.of(fe));
        assertEquals("Postal code size must be 5-10.", summary);
    }

    @Test
    void humaniseFieldName_leafOnlyFromDottedPath() {
        assertEquals("Recipient phone", GlobalExceptionHandler.humaniseFieldName("shipment.recipientPhone"));
        assertEquals("Hs code", GlobalExceptionHandler.humaniseFieldName("items[0].hsCode"));
        assertEquals("Customer no", GlobalExceptionHandler.humaniseFieldName("customerNo"));
        assertEquals("Field", GlobalExceptionHandler.humaniseFieldName(""));
        assertEquals("Field", GlobalExceptionHandler.humaniseFieldName(null));
    }

    // ─── PR #554 — catch-all + LinkageError humanisation ────────────

    @org.junit.jupiter.api.Test
    void runtimeException_yields_friendly_body_not_raw_message() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        // Simulate a nasty raw exception message the way Spring would
        // otherwise leak it.
        RuntimeException ex = new IllegalStateException(
                "sun.reflect.generics.reflectiveObjects.NotImplementedException at line 42");

        org.springframework.http.ResponseEntity<com.multiship.backend.dto.ApiResponse<Void>> resp =
                handler.handleUnexpectedRuntime(ex);

        assertEquals(500, resp.getStatusCode().value());
        com.multiship.backend.dto.ApiResponse<Void> body = resp.getBody();
        org.junit.jupiter.api.Assertions.assertNotNull(body);
        assertEquals("error", body.getStatus());
        assertEquals("INTERNAL_ERROR", body.getErrorCode());
        // The raw exception message must NOT leak — the user sees a
        // friendly line + a reference id.
        String userMessage = body.getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(userMessage.startsWith("Something went wrong"),
                "user-facing message must be friendly, not the raw exception text");
        org.junit.jupiter.api.Assertions.assertFalse(userMessage.contains("sun.reflect"),
                "internals leaked: " + userMessage);
        org.junit.jupiter.api.Assertions.assertFalse(userMessage.contains("NotImplementedException"),
                "class name leaked: " + userMessage);
        // A correlation id ("Reference XXXXXXXX") must be present for ops.
        org.junit.jupiter.api.Assertions.assertTrue(
                userMessage.matches(".*reference [0-9a-f]{8}\\..*"),
                "correlation id not embedded in user message: " + userMessage);
    }

    @org.junit.jupiter.api.Test
    void linkageError_stale_class_style_yields_friendly_body() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        // The exact class of exception the motivating incident threw.
        // NoSuchMethodError message shape: "'java.util.List
        // com.multiship.backend.dto.TrackingResponseDTO.$default$masterTrackings()'"
        NoSuchMethodError err = new NoSuchMethodError(
                "'java.util.List com.multiship.backend.dto.TrackingResponseDTO.$default$masterTrackings()'");

        org.springframework.http.ResponseEntity<com.multiship.backend.dto.ApiResponse<Void>> resp =
                handler.handleLinkageError(err);

        assertEquals(500, resp.getStatusCode().value());
        com.multiship.backend.dto.ApiResponse<Void> body = resp.getBody();
        org.junit.jupiter.api.Assertions.assertNotNull(body);
        org.junit.jupiter.api.Assertions.assertFalse(body.getMessage().contains("$default$"),
                "Lombok synthetic method name leaked: " + body.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(body.getMessage().contains("TrackingResponseDTO"),
                "DTO class name leaked: " + body.getMessage());
    }
}
