package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.dto.external.ExternalAddressValidationResponse;
import com.multiship.backend.service.external.ExternalApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Internal (operator-facing) address validation, used by the manual-shipment
 * page's "Validate" button. Shares the same structural checks as the public API.
 *
 * <p>Sprint 51 AC-L2 — the two overlapping paths ({@code /api/v1/addresses/validate}
 * here for structural checks and {@code /api/v1/address/validate} in
 * {@link AddressValidationController} for carrier-native validation) are
 * consolidated under {@code /api/v1/addresses/validate/structural} and
 * {@code /api/v1/addresses/validate/carrier}. The legacy paths remain
 * mapped and emit a WARN log so live callers keep working while ops
 * migrate. Delete after two consumer releases (target: Sprint 53).
 */
@Tag(name = "Addresses", description = "Validate a recipient address")
@RestController
@RequestMapping("/api/v1/addresses")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
@RequiredArgsConstructor
public class AddressController {

    private static final Logger log = LoggerFactory.getLogger(AddressController.class);

    private final ExternalApiService externalApiService;

    @Operation(summary = "Validate an address (structural)",
            description = "Structural sanity check — postal code / country / required fields. " +
                    "For carrier-native validation (UPS AVS, FedEx AV, ...) use /validate/carrier.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/validate/structural")
    public ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> validateStructural(@jakarta.validation.Valid @RequestBody ExternalAddress address) {
        return doValidate(address);
    }

    /**
     * @deprecated Sprint 51 AC-L2 — use {@link #validateStructural(ExternalAddress)}
     * at {@code /api/v1/addresses/validate/structural}. This route stays
     * mapped for two consumer releases (Sprint 51 → Sprint 53) so live
     * SPA callers upgrade without a coordinated deploy; emits a WARN on
     * every call so ops can spot the last caller.
     */
    @Deprecated
    @Operation(summary = "Validate an address (structural) — DEPRECATED",
            description = "Deprecated in Sprint 51 AC-L2; call /validate/structural instead. " +
                    "This path is removed in Sprint 53.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> validate(@jakarta.validation.Valid @RequestBody ExternalAddress address) {
        log.warn("Deprecated route hit: POST /api/v1/addresses/validate — migrate to /validate/structural (Sprint 51 AC-L2).");
        return doValidate(address);
    }

    private ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> doValidate(ExternalAddress address) {
        ExternalAddressValidationResponse res = externalApiService.validateAddress(address);
        return ResponseEntity.ok(ApiResponse.<ExternalAddressValidationResponse>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(res.isValid() ? "Address looks valid." : "Address has issues.")
                .data(res)
                .build());
    }
}
