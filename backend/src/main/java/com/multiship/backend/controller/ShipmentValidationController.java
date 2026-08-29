package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.ShipmentValidationResult;
import com.multiship.backend.service.ShipmentValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sprint 52 — powers the "Validate shipment" button on the New Shipment
 * page. Runs the same server-side guards that
 * {@link com.multiship.backend.service.CarrierServiceImpl#generateManualLabel}
 * runs (packaging compatibility, markup required, customs, DG,
 * allowlists) without calling the carrier's createShipment. When all
 * local checks pass, also calls the carrier's own validateAddress as a
 * partial substitute for a native validateShipment (per-carrier
 * validateShipment is a follow-up PR).
 *
 * <p>The request body reuses {@link ManualShipmentRequest} verbatim —
 * same shape as {@code POST /orders/manual-label} — so the FE can build
 * one payload from the form state and reuse it for both validation and
 * label generation.
 */
@Tag(name = "Shipment validation",
        description = "Server-side pre-flight for a manual shipment (Sprint 52)")
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentValidationController {

    private final ShipmentValidationService shipmentValidationService;

    @Operation(
            summary = "Validate a shipment payload before generating the label",
            description = "Runs all local guards (packaging compatibility, markup required, customs, DG, allowlists) "
                    + "then optionally calls the carrier's validateAddress. Returns a structured result with local "
                    + "errors, warnings, skipped checks, and the carrier address subresult. Never throws — "
                    + "validation failures are in the response body with overall=FAIL."
    )
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<ShipmentValidationResult>> validate(
            @RequestBody ManualShipmentRequest request) {
        ApiResponse<ShipmentValidationResult> response = shipmentValidationService.validate(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
