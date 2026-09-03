package com.multiship.backend.controller;

import com.multiship.backend.service.WebhookServiceImpl.WebhookProcessingOverloadedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #553 audit H1 — verifies the WebhookController's ExceptionHandler
 * maps WebhookProcessingOverloadedException → 503 SERVICE_UNAVAILABLE
 * with a Retry-After header. Pre-fix the exception propagated
 * unhandled and Spring's default handler emitted a generic 500 —
 * carriers treat 500 as a hard failure and may drop or slow-retry
 * the event. 503 is the idempotent-transient signal they know to
 * back off + retry on.
 */
class WebhookControllerOverloadedHandlerTest {

    @Test
    void overloaded_exception_maps_to_503_with_retry_after_header() throws Exception {
        WebhookController controller = new WebhookController(null);
        // Constructor is package-private (the exception is only meant to be
        // thrown from WebhookServiceImpl). Reflect it in for the test — no
        // need to widen the API contract just for a wire-level test.
        java.lang.reflect.Constructor<WebhookProcessingOverloadedException> ctor =
                WebhookProcessingOverloadedException.class.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        WebhookProcessingOverloadedException ex = ctor.newInstance("1Z999AA10123456784");

        ResponseEntity<Map<String, Object>> response = controller.handleOverloaded(ex);

        assertNotNull(response);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "must be 503 so carriers back off and retry idempotently, not 500 which they treat as hard-fail");
        assertEquals("60", response.getHeaders().getFirst("Retry-After"),
                "Retry-After hint helps carriers space out their re-post so we're not immediately re-saturated");
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("overloaded", body.get("status"));
        assertEquals("1Z999AA10123456784", body.get("trackingNumber"),
                "tracking number surfaced in body so ops can correlate a specific back-pressure event");
        assertTrue(String.valueOf(body.get("message")).contains("retry"),
                "user-facing message should hint that this is a retryable state, not a permanent failure");
    }
}
