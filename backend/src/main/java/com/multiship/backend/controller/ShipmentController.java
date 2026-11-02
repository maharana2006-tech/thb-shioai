package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.model.ShipmentAttempt;
import com.multiship.backend.service.shipment.ManualShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Manual Shipment redesign, Phase 0 — the shipment resource. Thin: auth,
 * the attempt key, HTTP codes. {@code POST /shipments/validate} stays in
 * {@link ShipmentValidationController}.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentController {

    private final ManualShipmentService shipments;

    @Operation(summary = "Purchase a manual shipment's label (Idempotency-Key required; same key replays)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<LabelGenerationResponse>> purchase(
            @Valid @RequestBody ManualShipmentRequest request,
            @AuthenticationPrincipal UserDetails user,
            @RequestHeader(value = "Idempotency-Key", required = false) String attemptKey,
            @RequestHeader(value = "X-Validation-Fingerprint", required = false) String fingerprint) {
        if (!StringUtils.hasText(attemptKey)) return missingKey();
        ApiResponse<LabelGenerationResponse> response = shipments.purchase(request, attemptKey, user, null, fingerprint);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Re-purchase a failed order's label on the same order number (409 if already generated)")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @PostMapping("/{orderNo}/attempts")
    public ResponseEntity<ApiResponse<LabelGenerationResponse>> reattempt(
            @PathVariable Integer orderNo,
            @Valid @RequestBody ManualShipmentRequest request,
            @AuthenticationPrincipal UserDetails user,
            @RequestHeader(value = "Idempotency-Key", required = false) String attemptKey,
            @RequestHeader(value = "X-Validation-Fingerprint", required = false) String fingerprint) {
        if (!StringUtils.hasText(attemptKey)) return missingKey();
        ApiResponse<LabelGenerationResponse> response = shipments.purchase(request, attemptKey, user, orderNo, fingerprint);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "State of one purchase attempt, for recovery after a lost connection")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/attempts/{key}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> attempt(@PathVariable String key, Authentication auth) {
        UUID uuid;
        try {
            uuid = UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        ShipmentAttempt a = shipments.attempt(uuid).orElse(null);
        boolean admin = auth != null && auth.getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
        if (a == null || (!admin && (auth == null || !auth.getName().equals(a.getActor())))) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("attemptKey", a.getAttemptKey().toString());
        body.put("state", a.getState());
        body.put("orderNo", a.getOrderNo());
        body.put("fingerprint", a.getFingerprint());
        body.put("errorCode", a.getErrorCode());
        body.put("errorMessage", a.getErrorMessage());
        body.put("createdAt", a.getCreatedAt());
        body.put("updatedAt", a.getUpdatedAt());
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now()).data(body).build());
    }

    private static ResponseEntity<ApiResponse<LabelGenerationResponse>> missingKey() {
        return ResponseEntity.badRequest().body(ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(400)
                .message("Idempotency-Key header is required: send one UUID per purchase attempt.")
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .timestamp(LocalDateTime.now()).build());
    }
}
