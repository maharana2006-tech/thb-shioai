package com.multiship.backend.controller;

import com.multiship.backend.dto.AddressValidationRequestDTO;
import com.multiship.backend.dto.AddressValidationResponseDTO;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.AddressValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sprint 31 — address validation endpoint. Given a destination address
 * and a carrier code, calls the carrier's own address validation API
 * (UPS AVS, FedEx AV, DHL address-validate, SWSIM CleanseAddress) and
 * returns the result: {@code matchLevel}, {@code classification}, and
 * a suggested corrected address when the carrier offers one.
 *
 * <p>Sprint 51 AC-L2 — canonical path is now
 * {@code /api/v1/addresses/validate/carrier}. The legacy
 * {@code /api/v1/address/validate} path is kept mapped with a WARN log
 * for two consumer releases (Sprint 51 → Sprint 53) so live SPA + API
 * callers migrate on their own cadence.
 */
@Tag(name = "Address validation",
        description = "Validate delivery addresses against a carrier's own database (Sprint 31)")
@RestController
@RequiredArgsConstructor
public class AddressValidationController {

    private static final Logger log = LoggerFactory.getLogger(AddressValidationController.class);

    private final AddressValidationService addressValidationService;

    @Operation(summary = "Validate a delivery address against a carrier's own database",
            description = "Delegates to UPS AVS / FedEx AV / DHL address-validate / SWSIM CleanseAddress. " +
                    "Response.matchLevel drives the UI badge: EXACT (green), CORRECTED (amber + suggestion), " +
                    "AMBIGUOUS (amber), NOT_FOUND (red), NOT_SUPPORTED (grey), ERROR (red).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/api/v1/addresses/validate/carrier")
    public ResponseEntity<ApiResponse<AddressValidationResponseDTO>> validateCarrier(
            @Valid @RequestBody AddressValidationRequestDTO request) {
        ApiResponse<AddressValidationResponseDTO> response = addressValidationService.validate(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /**
     * @deprecated Sprint 51 AC-L2 — use {@link #validateCarrier(AddressValidationRequestDTO)}
     * at {@code /api/v1/addresses/validate/carrier}. Legacy route removed in Sprint 53.
     */
    @Deprecated
    @Operation(summary = "Validate address (carrier) — DEPRECATED",
            description = "Deprecated in Sprint 51 AC-L2; call POST /api/v1/addresses/validate/carrier instead. " +
                    "This path is removed in Sprint 53.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/api/v1/address/validate")
    public ResponseEntity<ApiResponse<AddressValidationResponseDTO>> validateLegacy(
            @Valid @RequestBody AddressValidationRequestDTO request) {
        log.warn("Deprecated route hit: POST /api/v1/address/validate — migrate to /api/v1/addresses/validate/carrier (Sprint 51 AC-L2).");
        ApiResponse<AddressValidationResponseDTO> response = addressValidationService.validate(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
