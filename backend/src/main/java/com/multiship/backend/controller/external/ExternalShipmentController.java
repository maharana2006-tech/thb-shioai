package com.multiship.backend.controller.external;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.external.*;
import com.multiship.backend.service.external.ExternalApiException;
import com.multiship.backend.service.external.ExternalApiService;
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

    @Operation(summary = "List available services (and pricing when enabled) for a route")
    @PostMapping("/rates")
    public ResponseEntity<ApiResponse<ExternalRateResponse>> rates(
            @RequestBody ExternalRateRequest req, @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            return ok("Rates retrieved.", externalApiService.rate(requireApi(caller), req));
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    @Operation(summary = "Create a shipment and get back the label (+ commercial invoice for international)")
    @PostMapping("/shipments")
    public ResponseEntity<ApiResponse<ExternalShipmentResponse>> create(
            @RequestBody ExternalShipmentRequest req, @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            ExternalShipmentResponse res = externalApiService.createShipment(requireApi(caller), req);
            return ResponseEntity.status(201).body(ApiResponse.<ExternalShipmentResponse>builder()
                    .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                    .message("Shipment #" + res.getShipmentId() + " created.")
                    .data(res).build());
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    @Operation(summary = "Get the current tracking status for a shipment")
    @GetMapping("/shipments/{shipmentId}/tracking")
    public ResponseEntity<ApiResponse<ExternalTrackingResponse>> tracking(
            @PathVariable Long shipmentId, @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            return ok("Tracking retrieved.", externalApiService.track(requireApi(caller), shipmentId));
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    @Operation(summary = "Void a shipment (platform-level cancellation)")
    @PostMapping("/shipments/{shipmentId}/void")
    public ResponseEntity<ApiResponse<Map<String, Object>>> voidShipment(
            @PathVariable Long shipmentId, @AuthenticationPrincipal ApiKeyPrincipal caller) {
        try {
            return ok("Shipment voided.", externalApiService.voidShipment(requireApi(caller), shipmentId));
        } catch (ExternalApiException e) {
            return error(e);
        }
    }

    @Operation(summary = "Validate an address (structural)")
    @PostMapping("/addresses/validate")
    public ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> validate(
            @RequestBody ExternalAddress address, @AuthenticationPrincipal ApiKeyPrincipal caller) {
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
