package com.multiship.backend.controller.external.v2;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ExternalWebhookSubscriptionDTO;
import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.repository.ExternalWebhookSubscriptionRepository;
import com.multiship.backend.service.external.ExternalWebhookDispatcher;
import com.multiship.backend.service.external.WebhookUrlValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    /** Sprint 51 T3 finding #7 — SSRF guard applied on every save. */
    private final WebhookUrlValidator urlValidator;
    /** Audit W2 — invalidate the dispatcher's 60s subscription cache
     *  immediately after any write from the self-serve v2 path too. */
    private final ExternalWebhookDispatcher dispatcher;
    /** Audit R2 #336 — same at-rest envelope encryption as the admin
     *  path so self-serve v2 saves land as ciphertext too. */
    private final com.multiship.backend.service.external.WebhookSecretCipher secretCipher;

    @Operation(summary = "List my subscriptions")
    // Sprint 51 AC-M1 — enumerate the actual response shapes on every v2 endpoint.
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED — missing / invalid API key or Bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — token lacks the required role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "TENANT_RATE_LIMITED — per-API-key or per-tenant quota exceeded")
    })
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
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated (existing id)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — missing/blank required field or SSRF-rejected URL"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CROSS_TENANT_ACCESS_DENIED — id refers to another API key's subscription"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "WEBHOOK_SUBSCRIPTION_NOT_FOUND — update targeted an id that does not exist"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "TENANT_RATE_LIMITED")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ExternalWebhookSubscriptionDTO>> save(
            @RequestBody ExternalWebhookSubscriptionDTO body,
            @AuthenticationPrincipal ApiKeyPrincipal caller) {
        Long keyId = requireKey(caller);
        if (body.getEvent() == null) return bad("event is required");
        if (body.getUrl() == null || body.getUrl().isBlank()) return bad("url is required");
        if (body.getSecret() == null || body.getSecret().isBlank()) return bad("secret is required");
        // Sprint 51 T3 finding #7 — reject URLs pointing at private
        // networks / cloud metadata / non-https scheme. Fail-fast at
        // save time so no unsafe row ever lands in the DB.
        try {
            urlValidator.validate(body.getUrl());
        } catch (WebhookUrlValidator.WebhookUrlRejectedException ex) {
            return bad(ex.getMessage());
        }

        ExternalWebhookSubscription entity;
        if (body.getId() != null) {
            // Sprint 51 AC-L4 — split the old "return 400 for both" branch.
            // Missing id → 404; wrong owner → 403 so callers can branch on
            // errorCode and give the human the right remediation.
            entity = subscriptionRepo.findById(body.getId()).orElse(null);
            if (entity == null) return notFound();
            if (!keyId.equals(entity.getApiKeyId())) return crossTenant();
        } else {
            entity = new ExternalWebhookSubscription();
            entity.setApiKeyId(keyId);
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setEvent(body.getEvent());
        entity.setUrl(body.getUrl().trim());
        // Audit R2 #336 — envelope AES-GCM (see admin controller equivalent).
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
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CROSS_TENANT_ACCESS_DENIED — id belongs to another API key"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "WEBHOOK_SUBSCRIPTION_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "TENANT_RATE_LIMITED")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id,
                                                     @AuthenticationPrincipal ApiKeyPrincipal caller) {
        Long keyId = requireKey(caller);
        // Sprint 51 AC-M3 — old body was a silent 200 for both "id does
        // not exist" and "id belongs to someone else". Now callers get the
        // right status + errorCode.
        ExternalWebhookSubscription entity = subscriptionRepo.findById(id).orElse(null);
        if (entity == null) return notFound();
        if (!keyId.equals(entity.getApiKeyId())) return crossTenant();
        subscriptionRepo.delete(entity);
        dispatcher.invalidateSubscriptionCache();
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
