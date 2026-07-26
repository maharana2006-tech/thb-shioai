package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;
import com.multiship.backend.service.RateShopService;
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
 * Rate shopping endpoint — wraps {@link RateShopService#rateShop} so the
 * frontend can call one URL and get a merged, sorted rate list across
 * every carrier. Backend fans out the four connector calls in parallel;
 * see the service for per-carrier fallback + timeout behaviour.
 */
@Tag(name = "Rate shopping",
        description = "Merged rate quotes across UPS, FedEx, USPS and DHL")
@RestController
@RequestMapping("/api/v1/rate-shop")
@RequiredArgsConstructor
public class RateShopController {

    private final RateShopService rateShopService;

    @Operation(summary = "Compare rates across every configured carrier",
            description = "Fans out the shipment envelope to UPS, FedEx, USPS and DHL " +
                    "in parallel. Options come back cheapest-first; per-carrier status " +
                    "list distinguishes 'no credentials configured' (STUB) from " +
                    "'rate call failed' (ERROR) from 'returned N options' (LIVE). " +
                    "Never throws — carrier failures are surfaced in the response body.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<RateShopResponseDTO>> rateShop(
            @Valid @RequestBody RateShopRequestDTO request) {
        ApiResponse<RateShopResponseDTO> response = rateShopService.rateShop(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
