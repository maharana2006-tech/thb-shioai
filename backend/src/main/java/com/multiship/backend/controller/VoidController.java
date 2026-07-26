package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.service.VoidService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Void / cancel endpoint — wraps {@link VoidService}. Sprint 30.
 * POST /api/v1/orders/{orderNo}/void → carrier's void endpoint.
 */
@Tag(name = "Void labels",
        description = "Cancel a previously-issued label at the carrier (Sprint 30)")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class VoidController {

    private final VoidService voidService;

    @Operation(summary = "Void the label for an order",
            description = "Calls the carrier's void / cancel endpoint (UPS Void Shipment, " +
                    "FedEx Cancel Shipment, SWSIM CancelIndicium, DHL Delete Shipment). " +
                    "USPS + UPS refund the postage when the label hasn't been scanned yet. " +
                    "Idempotent — voiding an already-VOIDED order returns 200 without a " +
                    "carrier round-trip.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{orderNo}/void")
    public ResponseEntity<ApiResponse<VoidLabelResponseDTO>> voidLabel(@PathVariable Integer orderNo) {
        ApiResponse<VoidLabelResponseDTO> response = voidService.voidLabel(orderNo);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
