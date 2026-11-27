package com.multiship.backend.controller.ai;

import com.multiship.backend.dto.ai.ChatRequest;
import com.multiship.backend.dto.ai.HsSuggestRequest;
import com.multiship.backend.dto.ai.PackagingSuggestRequest;
import com.multiship.backend.dto.ai.ParseAddressRequest;
import com.multiship.backend.dto.ai.ReviewShipmentRequest;
import com.multiship.backend.dto.ai.ServiceRecommendRequest;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.service.ai.AddressAiService;
import com.multiship.backend.service.ai.ShipmateChatService;
import com.multiship.backend.service.ai.ShipmentAiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ShipmateChatService shipmateChatService;

    /** Longest a single chat reply may stay open. */
    private static final long CHAT_TIMEOUT_MS = 120_000L;

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

    /**
     * Shipmate chat. The reply is streamed as server-sent events, each a JSON
     * object: {@code {"type":"text","delta":"..."}} for the next piece of the
     * answer, or {@code {"type":"error"}} if the AI fails part-way. A failure
     * before the first piece is a normal error response instead (503 when the
     * AI is not configured, 429 from the tenant rate limit).
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest req) {
        Flux<String> reply = shipmateChatService.reply(req);
        SseEmitter emitter = new SseEmitter(CHAT_TIMEOUT_MS);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        // The browser going away (Stop, closed tab) must also stop the provider call and its token spend.
        Runnable stop = () -> {
            Disposable active = subscription.get();
            if (active != null) active.dispose();
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(ex -> stop.run());
        subscription.set(reply.subscribe(
                delta -> {
                    if (!send(emitter, Map.of("type", "text", "delta", delta))) stop.run();
                },
                ex -> {
                    send(emitter, Map.of("type", "error"));
                    emitter.complete();
                },
                emitter::complete));
        return emitter;
    }

    private static boolean send(SseEmitter emitter, Map<String, String> event) {
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            return false;
        }
    }
}
