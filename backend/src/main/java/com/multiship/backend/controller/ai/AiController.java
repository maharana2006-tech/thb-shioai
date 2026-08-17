package com.multiship.backend.controller.ai;

import com.multiship.backend.dto.ai.HsSuggestRequest;
import com.multiship.backend.dto.ai.PackagingSuggestRequest;
import com.multiship.backend.dto.ai.ParseAddressRequest;
import com.multiship.backend.dto.ai.ReviewShipmentRequest;
import com.multiship.backend.dto.ai.ServiceRecommendRequest;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.service.ai.AddressAiService;
import com.multiship.backend.service.ai.ShipmentAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI-assist endpoints for the manual-shipment operator UI. Every endpoint is
 * suggestion-only — results are returned for the user to confirm; nothing is
 * persisted here.
 *
 * <p>Sprint 51 BS-M2 — role gate via {@link PreAuthorize} at class level.
 * Previously the class relied only on the global {@code anyRequest().authenticated()}
 * check, which meant any role (including the API-key {@code ROLE_API}) could
 * hit these endpoints and spend OpenAI budget. Now restricted to human roles.
 * The tighter per-tenant rate limit lives in
 * {@code TenantRateLimitFilter.DEFAULT_AI_PATHS} (10/min/tenant vs. the
 * general 100/min budget).
 *
 * <p>Sprint 51 follow-up BS-M2 — per-tenant OpenAI token metering is
 * now emitted from {@code OpenAiClient} against Micrometer/Actuator
 * (wired by BP-M4). Scrape at {@code /actuator/prometheus}, series
 * {@code openai_prompt_tokens_total}, {@code openai_completion_tokens_total},
 * {@code openai_total_tokens_total}, {@code openai_requests_total} and
 * {@code openai_request_duration_seconds}, tagged by {@code tenant} +
 * {@code endpoint} (+ {@code outcome} on requests).
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class AiController {

    private final AddressAiService addressAiService;
    private final ShipmentAiService shipmentAiService;

    /** Ship from / Ship to — parse a free-text blob into a structured address. */
    @PostMapping("/parse-address")
    public ResponseEntity<ExternalAddress> parseAddress(@RequestBody ParseAddressRequest req) {
        return ResponseEntity.ok(addressAiService.parse(req == null ? null : req.getText()));
    }

    /** Items · commercial invoice — suggest an HS tariff code for one line. */
    @PostMapping("/suggest-hs")
    public ResponseEntity<Map<String, Object>> suggestHs(@RequestBody HsSuggestRequest req) {
        return ResponseEntity.ok(shipmentAiService.suggestHs(req));
    }

    /** Package & weight — recommend a package + estimate from the item list. */
    @PostMapping("/suggest-packaging")
    public ResponseEntity<Map<String, Object>> suggestPackaging(@RequestBody PackagingSuggestRequest req) {
        return ResponseEntity.ok(shipmentAiService.suggestPackaging(req));
    }

    /** Account & service — recommend a service + incoterm for the route. */
    @PostMapping("/recommend-service")
    public ResponseEntity<Map<String, Object>> recommendService(@RequestBody ServiceRecommendRequest req) {
        return ResponseEntity.ok(shipmentAiService.recommendService(req));
    }

    /** Shipment — pre-ship sanity review of the whole form. */
    @PostMapping("/review-shipment")
    public ResponseEntity<Map<String, Object>> reviewShipment(@RequestBody ReviewShipmentRequest req) {
        return ResponseEntity.ok(shipmentAiService.reviewShipment(req));
    }
}
