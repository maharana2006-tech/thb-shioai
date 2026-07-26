package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.service.ManifestService;
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
 * Sprint 34 — end-of-day close-out endpoint. Manifests a set of tracking
 * numbers at the carrier so the driver can accept the labelled parcels
 * during pickup.
 */
@Tag(name = "Manifests",
        description = "End-of-day close-out (UPS EOD / FedEx CloseShipment / SWSIM SCAN Form)")
@RestController
@RequestMapping("/api/v1/manifests")
@RequiredArgsConstructor
public class ManifestController {

    private final ManifestService manifestService;

    @Operation(summary = "Close out today's shipments",
            description = "Calls the carrier's end-of-day / manifest endpoint (UPS End of Day, " +
                    "FedEx CloseShipment, SWSIM CreateScanForm). Returns the carrier's manifest " +
                    "identifier + PDF (or URL) the driver signs at pickup. DHL manifests are " +
                    "implicit via the pickup request (Sprint 33) — the response has status=NOT_SUPPORTED.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<ManifestResponseDTO>> closeOut(
            @Valid @RequestBody ManifestRequestDTO request) {
        ApiResponse<ManifestResponseDTO> response = manifestService.closeOut(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
