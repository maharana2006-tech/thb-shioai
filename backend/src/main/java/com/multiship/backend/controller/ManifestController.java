package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CloseDayRequestDTO;
import com.multiship.backend.dto.EodEventDTO;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.service.ManifestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    // Sprint 50 Tier 0.5 PR E - controller-level role gate stays broad;
    // ManifestServiceImpl clamps request.customerNo to the caller's own
    // tenant via TenantScopeEnforcer so a scoped USER cannot close out a
    // foreign tenant's manifest even though the SpEL doesn't inspect the body.
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<ManifestResponseDTO>> closeOut(
            @Valid @RequestBody ManifestRequestDTO request) {
        ApiResponse<ManifestResponseDTO> response = manifestService.closeOut(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Close out a whole day for one carrier",
            description = "V135 — gathers the day's GENERATED, non-voided labels for the carrier " +
                    "(optionally scoped to a client / warehouse) and manifests them, without the " +
                    "caller listing tracking numbers. Powers Settings → Pickups & End-of-Day.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/close-day")
    public ResponseEntity<ApiResponse<ManifestResponseDTO>> closeDay(
            @Valid @RequestBody CloseDayRequestDTO request) {
        ApiResponse<ManifestResponseDTO> response = manifestService.closeOutForDay(
                request.getCarrierCode(), request.getCustomerNo(),
                request.getCloseDate(), request.getWarehouseCode(), "MANUAL");
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Recent pickup / end-of-day events",
            description = "V135 — the pickup/close audit log (manual + scheduled), newest first.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EodEventDTO>>> events(
            @RequestParam(defaultValue = "50") int limit) {
        List<EodEventDTO> data = manifestService.recentEvents(limit);
        return ResponseEntity.ok(ApiResponse.<List<EodEventDTO>>builder()
                .status("success").code(200).message("Recent pickup/close events.").data(data).build());
    }
}
