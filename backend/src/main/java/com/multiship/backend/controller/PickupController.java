package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.PickupRequestDTO;
import com.multiship.backend.dto.PickupResponseDTO;
import com.multiship.backend.service.PickupService;
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
 * Sprint 33 — schedule a courier pickup for one carrier. Routes to
 * UPS Create Pickup / FedEx Create Pickup / DHL Request Pickup / SWSIM
 * SchedulePickup based on the request's {@code carrierCode}.
 */
@Tag(name = "Pickups", description = "Schedule courier pickups (Sprint 33)")
@RestController
@RequestMapping("/api/v1/pickups")
@RequiredArgsConstructor
public class PickupController {

    private final PickupService pickupService;

    @Operation(summary = "Schedule a courier pickup",
            description = "Every carrier lets a shipper request that a driver come collect N parcels " +
                    "at a given address on a given date + time window. Returns the carrier's " +
                    "confirmation number (PRN / dispatchConfirmationNumber / ConfirmationNumber) " +
                    "which the operator can quote if the driver needs to look the pickup up.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<PickupResponseDTO>> schedule(
            @Valid @RequestBody PickupRequestDTO request) {
        ApiResponse<PickupResponseDTO> response = pickupService.schedule(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
