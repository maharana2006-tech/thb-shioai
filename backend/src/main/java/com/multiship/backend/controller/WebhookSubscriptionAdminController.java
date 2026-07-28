package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ExternalWebhookSubscriptionDTO;
import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.repository.ExternalWebhookSubscriptionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 46 — admin-facing management of the external webhook
 * subscriptions belonging to any API key. Distinct from the v2
 * external endpoint (which is scoped to the caller's own key) so
 * platform ops can help partners provision.
 */
@Tag(name = "Webhook subscriptions (admin)",
        description = "Sprint 46 — manage external webhook subscriptions across API keys")
@RestController
@RequestMapping("/api/v1/webhook-subscriptions")
@RequiredArgsConstructor
public class WebhookSubscriptionAdminController {

    private final ExternalWebhookSubscriptionRepository subscriptionRepo;

    @Operation(summary = "List subscriptions by apiKeyId (or all when omitted)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ExternalWebhookSubscriptionDTO>>> list(
            @RequestParam(name = "apiKeyId", required = false) Long apiKeyId) {
        List<ExternalWebhookSubscription> rows = apiKeyId != null
                ? subscriptionRepo.findByApiKeyIdOrderByEventAsc(apiKeyId)
                : subscriptionRepo.findAll();
        List<ExternalWebhookSubscriptionDTO> data = rows.stream()
                .map(s -> ExternalWebhookSubscriptionDTO.from(s, true)).toList();
        return ok(data);
    }

    @Operation(summary = "Create or update a subscription for any API key")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<ExternalWebhookSubscriptionDTO>> save(
            @RequestBody ExternalWebhookSubscriptionDTO body) {
        if (body.getApiKeyId() == null) return bad("apiKeyId is required");
        if (body.getEvent() == null)     return bad("event is required");
        if (body.getUrl() == null || body.getUrl().isBlank()) return bad("url is required");
        if (body.getSecret() == null || body.getSecret().isBlank()) return bad("secret is required");

        ExternalWebhookSubscription entity;
        if (body.getId() != null) {
            entity = subscriptionRepo.findById(body.getId()).orElse(null);
            if (entity == null) return bad("subscription not found");
        } else {
            entity = new ExternalWebhookSubscription();
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setApiKeyId(body.getApiKeyId());
        entity.setEvent(body.getEvent());
        entity.setUrl(body.getUrl().trim());
        entity.setSecret(body.getSecret());
        entity.setActive(body.getActive() == null ? Boolean.TRUE : body.getActive());
        entity.setUpdatedAt(LocalDateTime.now());
        ExternalWebhookSubscription saved = subscriptionRepo.save(entity);

        HttpStatus code = body.getId() == null ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(code).body(ApiResponse.<ExternalWebhookSubscriptionDTO>builder()
                .status("success").code(code.value()).message("Subscription saved")
                .data(ExternalWebhookSubscriptionDTO.from(saved, true))
                .build());
    }

    @Operation(summary = "Delete a subscription")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        subscriptionRepo.deleteById(id);
        return ok(null);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value()).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> bad(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode("VALIDATION_FAILED").build());
    }
}
