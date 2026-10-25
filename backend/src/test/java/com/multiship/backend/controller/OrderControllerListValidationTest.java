package com.multiship.backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-016 — the /orders list endpoint used to silently rewrite any
 * unknown {@code sortBy} to "orderNo" and any non-numeric
 * {@code batch} to "" (service digits-only sanitiser). Operators +
 * API integrators who sent typos got a different-than-expected sort
 * with no indication. These pin the new 400-on-bad-input contract.
 */
class OrderControllerListValidationTest {

    private OrderController controller;

    @BeforeEach
    void setUp() {
        // No dependencies needed for the validation branches — they
        // throw before the service is touched.
        controller = new OrderController();
    }

    @Test
    void unknownSortBy_throws400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listOrders(0, 25, "customerName" /* typo */, "ASC",
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, false));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Unknown sortBy"),
                "message names the field, got: " + ex.getReason());
    }

    @Test
    void unknownSortDirection_throws400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listOrders(0, 25, "orderNo", "ascending" /* not ASC/DESC */,
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, false));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Unknown sortDirection"));
    }

    @Test
    void nonNumericBatch_throws400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listOrders(0, 25, "orderNo", "ASC",
                        null, null, null, null, null, null, null, null,
                        "batch-14" /* letters, not a valid id */,
                        null, null, null, null, null, null, false));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Unknown batch"),
                "message names the field, got: " + ex.getReason());
    }

    @Test
    void blankBatch_passesValidation() {
        // Blank/null batch is the "not filtering by batch" sentinel.
        // The validation branches must not fire; downstream NPE from
        // the un-wired service proves we got past validation.
        Throwable ex = assertThrows(Throwable.class,
                () -> controller.listOrders(0, 25, "orderNo", "ASC",
                        null, null, null, null, null, null, null, null,
                        "" /* blank */,
                        null, null, null, null, null, null, false));
        // Should NOT be a 400 ResponseStatusException from our validators.
        assertTrue(!(ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                        && rse.getReason() != null
                        && rse.getReason().contains("Unknown batch")),
                "blank batch must not trip the batch validator");
    }
}
