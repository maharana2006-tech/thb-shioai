package com.multiship.backend.controller;

import com.multiship.backend.model.CarrierWebhookEvent;
import com.multiship.backend.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for the carrier-webhook receiver
 * (was 0-coverage). Focus on the 200 verified / 200 unsigned-audit /
 * 401 rejected branches — the audit's biggest carve-out (Sprint 49
 * Tier 0 closed the blank-secret bypass; those semantics must stay
 * pinned).
 */
class WebhookControllerTest {

    private WebhookService webhookService;
    private WebhookController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        webhookService = mock(WebhookService.class);
        controller = new WebhookController(webhookService);
        request = mock(HttpServletRequest.class);
        // Empty header enumeration by default; individual tests can override.
        Enumeration<String> empty = new Vector<String>().elements();
        when(request.getHeaderNames()).thenReturn(empty);
    }

    private static CarrierWebhookEvent event(Long id, boolean verified, boolean rejected) {
        CarrierWebhookEvent e = new CarrierWebhookEvent();
        e.setId(id);
        e.setCarrierCode("UPS");
        e.setTrackingNumber("1Z999");
        e.setVerified(verified);
        e.setDelivered(false);
        e.setRejected(rejected);
        return e;
    }

    @Test
    void receive_verified_returns200WithVerifiedTrue() {
        when(webhookService.receive(eq("ups"), anyString(), anyMap()))
                .thenReturn(event(1L, true, false));

        ResponseEntity<Map<String, Object>> resp =
                controller.receive("ups", "{\"scan\":\"DL\"}", request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(true, resp.getBody().get("verified"));
        assertEquals("UPS", resp.getBody().get("carrierCode"));
    }

    @Test
    void receive_unsignedButOptedIn_returns200VerifiedFalse() {
        // WebhookService returns verified=false + rejected=false when the
        // carrier is opted in to unsigned mode (webhook.unsigned.{c}=true).
        when(webhookService.receive(any(), any(), any())).thenReturn(event(2L, false, false));

        ResponseEntity<Map<String, Object>> resp =
                controller.receive("fedex", "{\"scan\":\"OF\"}", request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("verified"));
    }

    @Test
    void receive_rejected_returns401() {
        // Sprint 49 Tier 0 — blank secret + no unsigned opt-in ⇒ 401 refuse.
        when(webhookService.receive(any(), any(), any())).thenReturn(event(3L, false, true));

        ResponseEntity<Map<String, Object>> resp =
                controller.receive("dhl", "{\"scan\":\"X\"}", request);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("rejected"));
    }

    @Test
    void receive_snapshotsRequestHeaders_intoDelegateCall() {
        // Verify the header-snapshot loop populates the headers map so
        // signature verification has what it needs.
        Vector<String> names = new Vector<>(List.of("X-UPS-Signature", "Content-Type"));
        when(request.getHeaderNames()).thenReturn(names.elements());
        when(request.getHeader("X-UPS-Signature")).thenReturn("sig-abc");
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(webhookService.receive(any(), any(), any())).thenReturn(event(4L, true, false));

        controller.receive("ups", "{}", request);

        verify(webhookService).receive(eq("ups"), eq("{}"),
                eq(Collections.unmodifiableMap(Map.of(
                        "X-UPS-Signature", "sig-abc",
                        "Content-Type", "application/json"))));
    }

    @Test
    void receive_delivered_flagsBodyDeliveredTrue() {
        CarrierWebhookEvent e = event(5L, true, false);
        e.setDelivered(true);
        when(webhookService.receive(any(), any(), any())).thenReturn(e);

        ResponseEntity<Map<String, Object>> resp = controller.receive("ups", "{}", request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("delivered"));
    }
}
