package com.multiship.backend.controller;

import com.multiship.backend.model.CarrierWebhookEvent;
import com.multiship.backend.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sprint 36 — public webhook receiver for carrier push-tracking events.
 * No JWT auth — carriers can't produce our tokens. Signature
 * verification happens per carrier via HMAC-SHA256 in the appropriate
 * request header (X-UPS-Signature, X-FedEx-Signature, X-DHL-Signature,
 * X-Stamps-Signature).
 *
 * <p>Response semantics:
 * <ul>
 *   <li>200 with {@code verified=true} — signature verified; order state advanced.</li>
 *   <li>200 with {@code verified=false} — carrier is opted in to unsigned mode
 *       (webhook.unsigned.{carrier}=true); payload audited but order state untouched.</li>
 *   <li>200 with {@code verified=false} — signature was present but did not match;
 *       audited, no state change.</li>
 *   <li><b>401</b> with {@code rejected=true} — blank secret AND no unsigned opt-in.
 *       Sprint 49 Tier 0: closes the pre-existing bypass where unsigned webhooks
 *       could rewrite any shipment's status.</li>
 * </ul>
 */
@Tag(name = "Carrier webhooks",
        description = "Push-tracking receiver — no JWT, HMAC-SHA256 per carrier")
@RestController
@RequestMapping("/api/v1/webhooks/carrier")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final WebhookService webhookService;

    @Operation(summary = "Receive a push-tracking webhook",
            description = "Called by UPS, FedEx, DHL, or Stamps.com when a scan happens. " +
                    "Signature verification via HMAC-SHA256 against the shared secret " +
                    "configured at webhook.secrets.{carrier}. Verified events update the " +
                    "matching OrderTracking row; malformed / unverified events persist for audit.")
    @PostMapping("/{carrier}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String carrier,
            @RequestBody String rawPayload,
            HttpServletRequest request) {

        // Snapshot the headers into a Map<String, String> so the
        // connectors don't need Servlet API imports.
        Map<String, String> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }

        CarrierWebhookEvent saved = webhookService.receive(
                carrier, rawPayload, Collections.unmodifiableMap(headers));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", saved.getId());
        body.put("carrierCode", saved.getCarrierCode());
        body.put("trackingNumber", saved.getTrackingNumber());
        body.put("verified", saved.getVerified());
        body.put("delivered", saved.getDelivered());

        // Sprint 49 Tier 0: rejected rows return 401 so callers see the refusal.
        if (Boolean.TRUE.equals(saved.getRejected())) {
            body.put("rejected", true);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        return ResponseEntity.status(HttpStatus.OK).body(body);
    }
}
