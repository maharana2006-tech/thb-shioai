package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ExternalWebhookSubscriptionDTO;
import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.repository.ExternalWebhookSubscriptionRepository;
import com.multiship.backend.service.external.ExternalWebhookDispatcher;
import com.multiship.backend.service.external.WebhookUrlValidator;
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
    /** Sprint 51 T3 finding #7 — SSRF guard. Admin path is more privileged
     *  (can save subs on any api_key) so a hostile admin is out of scope,
     *  but a compromised admin session should still not be able to point
     *  a subscription at internal infra. */
    private final WebhookUrlValidator urlValidator;
    /** Audit W2 — invalidate the dispatcher's 60s subscription cache
     *  the moment a sub is written / removed so the change propagates
     *  immediately instead of racing the TTL. */
    private final ExternalWebhookDispatcher dispatcher;
    /** Audit R2 #336 — envelope-encrypt the HMAC secret on save so DB
     *  dumps don't leak every tenant's signing key. */
    private final com.multiship.backend.service.external.WebhookSecretCipher secretCipher;

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
        // Sprint 51 T3 finding #7 — same SSRF guard as the v2 external
        // save path. Applied even for admin callers.
        try {
            urlValidator.validate(body.getUrl());
        } catch (WebhookUrlValidator.WebhookUrlRejectedException ex) {
            return bad(ex.getMessage());
        }

        ExternalWebhookSubscription entity;
        if (body.getId() != null) {
            // Sprint 51 AC-L4 — 404 rather than 400 on unknown id so
            // callers can distinguish "your payload is bad" (400) from
            // "the row you referenced does not exist" (404). Cross-tenant
            // mismatch — the update body targets an id owned by a
            // different api_key — returns 403 CROSS_TENANT_ACCESS_DENIED
            // so ops sees the wire-crossing signal even at the admin path.
            entity = subscriptionRepo.findById(body.getId()).orElse(null);
            if (entity == null) return notFound();
            if (entity.getApiKeyId() != null
                    && !entity.getApiKeyId().equals(body.getApiKeyId())) {
                return crossTenant();
            }
        } else {
            entity = new ExternalWebhookSubscription();
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setApiKeyId(body.getApiKeyId());
        entity.setEvent(body.getEvent());
        entity.setUrl(body.getUrl().trim());
        // Audit R2 #336 — encrypt-at-rest via envelope AES-GCM. Pre-fix
        // this was setSecret(plaintext); now the plaintext column is
        // nulled at the same time so a DB dump doesn't leak the key.
        try {
            secretCipher.encryptOnSave(entity, body.getSecret());
        } catch (IllegalStateException cryptoUnavailable) {
            return bad(cryptoUnavailable.getMessage());
        }
        entity.setActive(body.getActive() == null ? Boolean.TRUE : body.getActive());
        entity.setUpdatedAt(LocalDateTime.now());
        ExternalWebhookSubscription saved = subscriptionRepo.save(entity);
        dispatcher.invalidateSubscriptionCache();

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
        // Audit W3 — return 404 on missing id (v2 controller does the same,
        // so callers get a consistent errorCode across paths). Old body was
        // a silent 200 that masked stale-id bugs.
        ExternalWebhookSubscription entity = subscriptionRepo.findById(id).orElse(null);
        if (entity == null) return notFound();
        subscriptionRepo.delete(entity);
        dispatcher.invalidateSubscriptionCache();
        return ok(null);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value()).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> bad(String message) {
        // Sprint 51 AC-M2 — was the string literal "VALIDATION_FAILED"
        // (not in the ErrorCode enum); now the canonical VALIDATION_ERROR.
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.NOT_FOUND.value())
                .message("subscription not found")
                .errorCode(ErrorCode.WEBHOOK_SUBSCRIPTION_NOT_FOUND.name()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> crossTenant() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.FORBIDDEN.value())
                .message("subscription belongs to a different API key")
                .errorCode(ErrorCode.CROSS_TENANT_ACCESS_DENIED.name()).build());
    }
}
