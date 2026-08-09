package com.multiship.backend.controller.external.v2;

import com.multiship.backend.config.ApiKeyPrincipal;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 46 — CRUD for the caller's own webhook subscriptions.
 * Subscriptions are scoped to the caller's API key: an external app can
 * only see and manage its own subs.
 */
@Tag(name = "Public Shipping API v2 · Webhooks",
        description = "Sprint 46 — subscribe to LABEL_GENERATED / TRACKING_UPDATED / EXCEPTION / RULE_BLOCKED events")
@RestController
@RequestMapping("/api/v2/external/webhooks")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('API', 'ADMIN')")
public class ExternalWebhookController {

    private final ExternalWebhookSubscriptionRepository subscriptionRepo;

    @Operation(summary = "List my subscriptions")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ExternalWebhookSubscriptionDTO>>> list(
            @AuthenticationPrincipal ApiKeyPrincipal caller) {
        Long keyId = requireKey(caller);
        List<ExternalWebhookSubscriptionDTO> data = subscriptionRepo
                .findByApiKeyIdOrderByEventAsc(keyId).stream()
                .map(s -> ExternalWebhookSubscriptionDTO.from(s, true))
                .toList();
        return ok(data, "Subscriptions loaded");
    }

    @Operation(summary = "Create or update a subscription",
            description = "Body: {event, url, secret, active}. Secret is the HMAC-SHA256 key we use to sign delivered payloads.")
    @PostMapping
    public ResponseEntity<ApiResponse<ExternalWebhookSubscriptionDTO>> save(
            @RequestBody ExternalWebhookSubscriptionDTO body,
            @AuthenticationPrincipal ApiKeyPrincipal caller) {
        Long keyId = requireKey(caller);
        if (body.getEvent() == null) return bad("event is required");
        if (body.getUrl() == null || body.getUrl().isBlank()) return bad("url is required");
        if (body.getSecret() == null || body.getSecret().isBlank()) return bad("secret is required");

        ExternalWebhookSubscription entity;
        if (body.getId() != null) {
            entity = subscriptionRepo.findById(body.getId()).orElse(null);
            if (entity == null || !keyId.equals(entity.getApiKeyId())) return bad("subscription not found");
        } else {
            entity = new ExternalWebhookSubscription();
            entity.setApiKeyId(keyId);
            entity.setCreatedAt(LocalDateTime.now());
        }
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
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id,
                                                     @AuthenticationPrincipal ApiKeyPrincipal caller) {
        Long keyId = requireKey(caller);
        subscriptionRepo.findById(id)
                .filter(s -> keyId.equals(s.getApiKeyId()))
                .ifPresent(subscriptionRepo::delete);
        return ok(null, "Deleted");
    }

    // ===== helpers =====

    private static Long requireKey(ApiKeyPrincipal caller) {
        if (caller == null || caller.getApiKeyId() == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Webhook management requires an API key or an OAuth client-credentials token.");
        }
        return caller.getApiKeyId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value())
                .message(message).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> bad(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode("VALIDATION_FAILED").build());
    }
}
