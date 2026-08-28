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
}
