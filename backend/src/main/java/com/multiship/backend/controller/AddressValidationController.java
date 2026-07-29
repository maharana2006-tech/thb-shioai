package com.multiship.backend.controller;

import com.multiship.backend.dto.AddressValidationRequestDTO;
import com.multiship.backend.dto.AddressValidationResponseDTO;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.AddressValidationService;
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
 * Sprint 31 — address validation endpoint. Given a destination address
 * and a carrier code, calls the carrier's own address validation API
 * (UPS AVS, FedEx AV, DHL address-validate, SWSIM CleanseAddress) and
 * returns the result: {@code matchLevel}, {@code classification}, and
 * a suggested corrected address when the carrier offers one.
 */
@Tag(name = "Address validation",
        description = "Validate delivery addresses against a carrier's own database (Sprint 31)")
@RestController
@RequestMapping("/api/v1/address")
@RequiredArgsConstructor
public class AddressValidationController {

    private final AddressValidationService addressValidationService;

    @Operation(summary = "Validate a delivery address against a carrier's own database",
            description = "Delegates to UPS AVS / FedEx AV / DHL address-validate / SWSIM CleanseAddress. " +
                    "Response.matchLevel drives the UI badge: EXACT (green), CORRECTED (amber + suggestion), " +
                    "AMBIGUOUS (amber), NOT_FOUND (red), NOT_SUPPORTED (grey), ERROR (red).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<AddressValidationResponseDTO>> validate(
            @Valid @RequestBody AddressValidationRequestDTO request) {
        ApiResponse<AddressValidationResponseDTO> response = addressValidationService.validate(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
