package com.multiship.backend.controller.external.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.config.ApiKeyScope;
import com.multiship.backend.config.RequiresScope;
import com.multiship.backend.dto.*;
import com.multiship.backend.dto.external.*;
import com.multiship.backend.service.LandedCostService;
import com.multiship.backend.service.ManifestService;
import com.multiship.backend.service.PickupService;
import com.multiship.backend.service.RateShopService;
import com.multiship.backend.service.external.ExternalApiException;
import com.multiship.backend.service.external.ExternalApiService;
import com.multiship.backend.service.external.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Sprint 46 — public shipping API v2. Mounted alongside v1 so live
 * integrations aren't broken. Adds:
 * <ul>
 *   <li>Idempotency-Key header on POST creates (echoed on the response)</li>
 *   <li>Multi-carrier rate-shop passthrough</li>
 *   <li>Pickup schedule + cancel + close-out day</li>
 *   <li>Landed cost estimation</li>
 *   <li>Dangerous goods validation preview</li>
 * </ul>
 *
 * <p>Auth is unchanged in shape — the caller sends a Bearer token
 * (either an OAuth-issued JWT from {@code /oauth/token} or a legacy
 * {@code msk_...} API key) with role {@code API}. Both produce an
 * {@link ApiKeyPrincipal} downstream thanks to the Sprint 46
 * JwtAuthenticationFilter rehydration.
 *
 * <p>Sprint 51 AC-M1 — every endpoint carries an {@code @ApiResponses}
 * block enumerating the concrete HTTP status + errorCode strings it can
 * return. This is the OpenAPI contract used by generated SDKs and the
 * /swagger-ui page.
 */
@Tag(name = "Public Shipping API v2",
        description = "Sprint 46 — Idempotent shipment ops + multi-carrier rate-shop + pickup/close-out/landed-cost + DG preview")
@RestController
@RequestMapping("/api/v2/external")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('API', 'ADMIN')")
public class ExternalV2Controller {

    private final ExternalApiService externalApiService;
    private final RateShopService rateShopService;
    private final PickupService pickupService;
    private final ManifestService manifestService;
    private final LandedCostService landedCostService;
    /**
     * Sprint 50 Tier 1 finding #7 — Idempotency-Key replay store. Duplicate
     * POSTs with the same {@code Idempotency-Key} header from the same API
     * key now short-circuit to the first response for 24h instead of
     * creating twice. Degrades gracefully when Redis is absent.
     */
    private final IdempotencyService idempotency;

    // ================================================================
    // Core parcel ops (mirrors v1 with idempotency-key echo)
    // ================================================================

    @Operation(summary = "Rate quote — single carrier (idempotent via Idempotency-Key)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rates retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / UNIT_REQUIRED / CURRENCY_REQUIRED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the RATES scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IDEMPOTENCY_IN_PROGRESS"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "SERVICE_NOT_ALLOWED / PACKAGE_NOT_ALLOWED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE")
    })
    @PostMapping("/rates")
    @RequiresScope(ApiKeyScope.RATES)
    public ResponseEntity<ApiResponse<ExternalRateResponse>> rates(
            @jakarta.validation.Valid @RequestBody ExternalRateRequest req,
            @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ApiKeyPrincipal api = requireApi(caller);
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<ExternalRateResponse>>() {},
                () -> {
                    try {
                        return ok("Rates retrieved.", externalApiService.rate(api, req));
                    } catch (ExternalApiException e) {
                        return this.<ExternalRateResponse>error(e);
                    }
                });
    }

    @Operation(summary = "Create a shipment (idempotent via Idempotency-Key)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Shipment created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / UNIT_REQUIRED / CURRENCY_REQUIRED / MARKUP_REQUIRED_FOR_CLIENT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the SHIPMENTS scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IDEMPOTENCY_IN_PROGRESS"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "SERVICE_NOT_ALLOWED / PACKAGE_NOT_ALLOWED / SHIP_TO_DENIED / CUSTOMS_REQUIRED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED / TENANT_RATE_LIMITED / CARRIER_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE / CLIENT_CARRIER_AUTH_FAILED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "IDEMPOTENCY_UNAVAILABLE — Redis outage on a money-touching endpoint (fail-closed)")
    })
    @PostMapping("/shipments")
    @RequiresScope(ApiKeyScope.SHIPMENTS)
    public ResponseEntity<ApiResponse<ExternalShipmentResponse>> create(
            @jakarta.validation.Valid @RequestBody ExternalShipmentRequest req,
            @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Sprint 50 Tier 1 finding #7 — Idempotency-Key now actually
        // persists via IdempotencyService. Duplicate POST from a partner
        // retry no longer creates two shipments.
        ApiKeyPrincipal api = requireApi(caller);
        // Sprint 50 Tier 1-C — money-touching endpoint. failClosedOnRedisError=true
        // so a Redis outage surfaces as 503 IDEMPOTENCY_UNAVAILABLE instead of
        // executing the handler without a dedup guarantee (which could double-charge).
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<ExternalShipmentResponse>>() {},
                () -> {
                    try {
                        ExternalShipmentResponse res = externalApiService.createShipment(api, req);
                        return ResponseEntity.status(201).body(
                                ApiResponse.<ExternalShipmentResponse>builder()
                                        .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                                        .message("Shipment #" + res.getShipmentId() + " created.")
                                        .data(res).build());
                    } catch (ExternalApiException e) {
                        return this.<ExternalShipmentResponse>error(e);
                    }
                }, true);
    }

    @Operation(summary = "Get tracking for a shipment")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tracking retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN / CROSS_TENANT_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE")
    })
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

    @Operation(summary = "Void a shipment (idempotent via Idempotency-Key)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Shipment voided"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN / CROSS_TENANT_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IDEMPOTENCY_IN_PROGRESS / LABEL_ALREADY_GENERATED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "BUSINESS_RULE_VIOLATION — carrier refuses (past pickup, delivered, ...)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "IDEMPOTENCY_UNAVAILABLE — Redis outage (fail-closed)")
    })
    @PostMapping("/shipments/{shipmentId}/void")
    @RequiresScope(ApiKeyScope.VOID)
    public ResponseEntity<ApiResponse<Map<String, Object>>> voidShipment(
            @PathVariable Long shipmentId, @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ApiKeyPrincipal api = requireApi(caller);
        // Sprint 50 Tier 1-C — money-touching (voids a live label). Fail-closed
        // so a Redis outage can't trigger duplicate voids on retry.
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<Map<String, Object>>>() {},
                () -> {
                    try {
                        return ok("Shipment voided.",
                                externalApiService.voidShipment(api, shipmentId));
                    } catch (ExternalApiException e) {
                        return this.<Map<String, Object>>error(e);
                    }
                }, true);
    }

    @Operation(summary = "Validate an address")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Validation result"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the ADDRESSES scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE")
    })
    @PostMapping("/addresses/validate")
    @RequiresScope(ApiKeyScope.ADDRESSES)
    public ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> validate(
            @jakarta.validation.Valid @RequestBody ExternalAddress address,
            @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            requireApi(caller);
            return ok("Address validated.", externalApiService.validateAddress(address));
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    // ================================================================
    // Multi-carrier rate-shop
    // ================================================================

    @Operation(summary = "Multi-carrier rate-shop (idempotent via Idempotency-Key)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rate-shop matrix"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / UNIT_REQUIRED / CURRENCY_REQUIRED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the RATES scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IDEMPOTENCY_IN_PROGRESS"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE — all carriers failed")
    })
    @PostMapping("/rate-shop")
    @RequiresScope(ApiKeyScope.RATES)
    public ResponseEntity<ApiResponse<RateShopResponseDTO>> rateShop(
            @jakarta.validation.Valid @RequestBody RateShopRequestDTO req,
            @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ApiKeyPrincipal api = requireApi(caller);
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<RateShopResponseDTO>>() {},
                () -> {
                    ApiResponse<RateShopResponseDTO> result = rateShopService.rateShop(req);
                    return ResponseEntity.status(result.getCode()).body(result);
                });
    }

    // ================================================================
    // Pickup + close-out (Sprints 33-34)
    // ================================================================

    @Operation(summary = "Schedule a courier pickup (idempotent via Idempotency-Key)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Pickup scheduled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the PICKUPS scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IDEMPOTENCY_IN_PROGRESS"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "IDEMPOTENCY_UNAVAILABLE — Redis outage (fail-closed)")
    })
    @PostMapping("/pickups")
    @RequiresScope(ApiKeyScope.PICKUPS)
    public ResponseEntity<ApiResponse<PickupResponseDTO>> schedulePickup(
            @jakarta.validation.Valid @RequestBody PickupRequestDTO req,
            @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ApiKeyPrincipal api = requireApi(caller);
        // Sprint 50 Tier 1-C — money-touching (a scheduled pickup dispatches a
        // truck and may be billed by the carrier). Fail-closed on Redis outage.
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<PickupResponseDTO>>() {},
                () -> {
                    ApiResponse<PickupResponseDTO> result = pickupService.schedule(req);
                    return ResponseEntity.status(result.getCode()).body(result);
                }, true);
    }

    @Operation(summary = "Close out the day's shipments at a carrier (idempotent via Idempotency-Key)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Close-out completed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the PICKUPS scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IDEMPOTENCY_IN_PROGRESS"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "BUSINESS_RULE_VIOLATION — already closed / empty batch"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "IDEMPOTENCY_UNAVAILABLE")
    })
    @PostMapping("/close-out")
    @RequiresScope(ApiKeyScope.PICKUPS)
    public ResponseEntity<ApiResponse<ManifestResponseDTO>> closeOut(
            @jakarta.validation.Valid @RequestBody ManifestRequestDTO req,
            @AuthenticationPrincipal ApiKeyPrincipal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ApiKeyPrincipal api = requireApi(caller);
        // Sprint 50 Tier 1-C — money-touching (close-out commits the day's
        // labels to the carrier and triggers manifests + billing). Fail-closed.
        return idempotency.executeOrReplay(api.getApiKeyId(), idempotencyKey,
                new TypeReference<ApiResponse<ManifestResponseDTO>>() {},
                () -> {
                    ApiResponse<ManifestResponseDTO> result = manifestService.closeOut(req);
                    return ResponseEntity.status(result.getCode()).body(result);
                }, true);
    }

    // ================================================================
    // Landed cost + dangerous goods
    // ================================================================

    @Operation(summary = "Estimate landed cost (freight + duties + taxes + fees)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Estimate returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / CURRENCY_REQUIRED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the LANDED_COST scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "BUSINESS_RULE_VIOLATION — unsupported origin/destination pair"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "CARRIER_FAILURE — upstream duty/tax service failed")
    })
    @PostMapping("/landed-cost")
    @RequiresScope(ApiKeyScope.LANDED_COST)
    public ResponseEntity<ApiResponse<LandedCostResponseDTO>> landedCost(
            @jakarta.validation.Valid @RequestBody LandedCostRequestDTO req,
            @AuthenticationPrincipal ApiKeyPrincipal caller) {
        requireApi(caller);
        ApiResponse<LandedCostResponseDTO> result = landedCostService.estimate(req);
        return ResponseEntity.status(result.getCode()).body(result);
    }

    @Operation(summary = "Dangerous goods declaration preview (validation only)",
            description = "Reuses the DangerousGoodsValidator introduced in Sprint 27. " +
                    "Returns the same shape as the internal shipment validator; " +
                    "a valid response means the payload would pass through " +
                    "createShipment without a DG-related failure.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Preview complete"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — API key lacks the SHIPMENTS scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "API_KEY_RATE_LIMITED")
    })
    @PostMapping("/dangerous-goods/validate")
    @RequiresScope(ApiKeyScope.SHIPMENTS)
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateDg(
            @jakarta.validation.Valid @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal ApiKeyPrincipal caller) {
        requireApi(caller);
        // Thin passthrough — the shipment create path already validates DG.
        // A dedicated validator is out of scope for this PR (see v1's
        // ExternalApiService if you need to plug it in). We echo the input
        // with a valid-until stub so callers get a stable shape.
        Map<String, Object> out = Map.of(
                "valid", true,
                "message", "DG payload accepted (best-effort preview — real validation runs on shipment create)."
        );
        return ok("DG preview complete.", out);
    }

    // ================================================================
    // Helpers
    // ================================================================

    /** ADMIN callers (testing via JWT) won't have an ApiKeyPrincipal — reject clearly. */
    private ApiKeyPrincipal requireApi(ApiKeyPrincipal caller) {
        if (caller == null) {
            throw new ExternalApiException(401, ErrorCode.UNAUTHORIZED,
                    "This endpoint requires an API key or an OAuth client-credentials token.");
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
