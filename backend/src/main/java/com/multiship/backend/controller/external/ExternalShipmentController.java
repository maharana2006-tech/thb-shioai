package com.multiship.backend.controller.external;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.config.ApiKeyScope;
import com.multiship.backend.config.RequiresScope;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.external.*;
import com.multiship.backend.service.external.ExternalApiException;
import com.multiship.backend.service.external.ExternalApiService;
import com.multiship.backend.service.external.IdempotencyService;
import com.fasterxml.jackson.core.type.TypeReference;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The public shipping API for external applications. Authenticate with an API
 * key via the {@code X-API-Key} header (or {@code Authorization: Bearer msk_...}).
 * Every operation is scoped to the client the key was issued for.
 */
@Tag(name = "External Shipping API", description = "Rate, create, track, and void shipments with an API key")
@RestController
@RequestMapping("/api/v1/external")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('API', 'ADMIN')")
public class ExternalShipmentController {

    private final ExternalApiService externalApiService;

    /**
     * Sprint 51 R3 — mirrors the v2 pattern. Sprint 50 Tier 1 wrapped v2
     * external in IdempotencyService; v1 external stayed live for existing
     * partners and remained unprotected. See audit finding #3.
     */
    private final IdempotencyService idempotency;

    @Operation(summary = "List available services (and pricing when enabled) for a route")
    @PostMapping("/rates")
    @RequiresScope(ApiKeyScope.RATES)
    public ResponseEntity<ApiResponse<ExternalRateResponse>> rates(
            @RequestBody ExternalRateRequest req, @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            return ok("Rates retrieved.", externalApiService.rate(requireApi(caller), req));
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    @Operation(summary = "Create a shipment and get back the label (+ commercial invoice for international)",
            description = "Idempotent via Idempotency-Key (Sprint 51 R3). A partner's automatic retry on timeout no longer creates duplicate shipments.")
    @PostMapping("/shipments")
    @RequiresScope(ApiKeyScope.SHIPMENTS)
    public ResponseEntity<ApiResponse<ExternalShipmentResponse>> create(
            @jakarta.validation.Valid @RequestBody ExternalShipmentRequest req, @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Sprint 51 R3 (audit finding #3) — mirror the v2 pattern. Money-touching
        // → failClosedOnRedisError=true so a Redis outage surfaces as 503
        // IDEMPOTENCY_UNAVAILABLE instead of executing the handler without
        // the dedup guarantee (which could double-charge).
        ApiKeyPrincipal api = requireApi(caller);
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<ExternalShipmentResponse>>() {},
                () -> {
                    try {
                        ExternalShipmentResponse res = externalApiService.createShipment(api, req);
                        return ResponseEntity.status(201).body(ApiResponse.<ExternalShipmentResponse>builder()
                                .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                                .message("Shipment #" + res.getShipmentId() + " created.")
                                .data(res).build());
                    } catch (ExternalApiException e) {
                        return this.<ExternalShipmentResponse>error(e);
                    }
                }, true);
    }

    @Operation(summary = "Get the current tracking status for a shipment")
    @GetMapping("/shipments/{shipmentId}/tracking")
    @RequiresScope(ApiKeyScope.TRACKING)
    public ResponseEntity<ApiResponse<ExternalTrackingResponse>> tracking(
            @PathVariable Long shipmentId, @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            return ok("Tracking retrieved.", externalApiService.track(requireApi(caller), shipmentId));
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    @Operation(summary = "Void a shipment (platform-level cancellation)",
            description = "Idempotent via Idempotency-Key (Sprint 51 R3). Retrying a void on the same shipmentId with the same key returns the first response instead of triggering a second carrier round-trip.")
    @PostMapping("/shipments/{shipmentId}/void")
    @RequiresScope(ApiKeyScope.VOID)
    public ResponseEntity<ApiResponse<Map<String, Object>>> voidShipment(
            @PathVariable Long shipmentId, @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Sprint 51 R3 (audit finding #3) — money-touching (voids a live label
        // that some carriers refund only on the first void). Fail-closed so
        // a Redis outage can't trigger duplicate voids on retry.
        ApiKeyPrincipal api = requireApi(caller);
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<Map<String, Object>>>() {},
                () -> {
                    try {
                        return ok("Shipment voided.", externalApiService.voidShipment(api, shipmentId));
                    } catch (ExternalApiException e) {
                        return this.<Map<String, Object>>error(e);
                    }
                }, true);
    }

    @Operation(summary = "Validate an address (structural)")
    @PostMapping("/addresses/validate")
    @RequiresScope(ApiKeyScope.ADDRESSES)
    public ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> validate(
            @jakarta.validation.Valid @RequestBody ExternalAddress address, @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            requireApi(caller);
            return ok("Address validated.", externalApiService.validateAddress(address));
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    // ── helpers ──

    /** ADMIN callers (testing via JWT) won't have an ApiKeyPrincipal — reject clearly. */
    private ApiKeyPrincipal requireApi(ApiKeyPrincipal caller) {
        if (caller == null) {
            throw new ExternalApiException(401, com.multiship.backend.dto.ErrorCode.UNAUTHORIZED,
                    "This endpoint requires an API key (X-API-Key header).");
        }
        return caller;
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now()).message(message).data(data).build());
    }

    private <T> ResponseEntity<ApiResponse<T>> error(ExternalApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.<T>builder()
                .status("ERROR").code(e.getStatus()).timestamp(LocalDateTime.now())
                .message(e.getMessage()).errorCode(e.getErrorCode().name()).build());
    }
}
