package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.dto.external.ExternalAddressValidationResponse;
import com.multiship.backend.service.external.ExternalApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Internal (operator-facing) address validation, used by the manual-shipment
 * page's "Validate" button. Shares the same structural checks as the public API.
 */
@Tag(name = "Addresses", description = "Validate a recipient address")
@RestController
@RequestMapping("/api/v1/addresses")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
@RequiredArgsConstructor
public class AddressController {

    private final ExternalApiService externalApiService;

    @Operation(summary = "Validate an address (structural)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<ExternalAddressValidationResponse>> validate(@RequestBody ExternalAddress address) {
        ExternalAddressValidationResponse res = externalApiService.validateAddress(address);
        return ResponseEntity.ok(ApiResponse.<ExternalAddressValidationResponse>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(res.isValid() ? "Address looks valid." : "Address has issues.")
                .data(res)
                .build());
    }
}
