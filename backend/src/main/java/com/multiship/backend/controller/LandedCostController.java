package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LandedCostRequestDTO;
import com.multiship.backend.dto.LandedCostResponseDTO;
import com.multiship.backend.service.LandedCostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sprint 32 — estimate landed cost (freight + duties + taxes + fees)
 * for a cross-border shipment via the carrier's own duty engine
 * (UPS Landed Cost, FedEx EDT, DHL Duties + Taxes). USPS is
 * domestic-only and returns {@code source=NOT_SUPPORTED}.
 */
@Tag(name = "Landed cost",
        description = "Duties + taxes + freight estimate for cross-border shipments")
@RestController
@RequestMapping("/api/v1/landed-cost")
@RequiredArgsConstructor
public class LandedCostController {

    private final LandedCostService landedCostService;

    @Operation(summary = "Estimate landed cost for a shipment",
            description = "Backend picks between UPS Landed Cost, FedEx EDT, and DHL Duties + Taxes " +
                    "based on carrierCode. Requires an international lane and a customs block; " +
                    "domestic shipments come back with source=NOT_SUPPORTED and no charge breakdown.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<LandedCostResponseDTO>> estimate(
            @Valid @RequestBody LandedCostRequestDTO request) {
        ApiResponse<LandedCostResponseDTO> response = landedCostService.estimate(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
