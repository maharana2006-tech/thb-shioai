package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountDTO;
import com.multiship.backend.dto.CarrierConnectRequest;
import com.multiship.backend.dto.CarrierConnectResponse;
import com.multiship.backend.dto.CarrierListResponse;
import com.multiship.backend.dto.CarrierStatusResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.service.CarrierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Carriers", description = "Carrier connections, credential management, and label generation")
@RestController
@RequestMapping("/api/v1/carriers")
@RequiredArgsConstructor
public class CarrierController {

    private final CarrierService carrierService;

    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CarrierListResponse>>> getAvailableCarriers() {
        ApiResponse<List<CarrierListResponse>> response = carrierService.getAvailableCarriers();
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Connect a carrier account (ADMIN)",
            description = "Accepts canonical carrier codes UPS / FEDEX / USPS (legacy P80/F77/L01 tolerated). Optional tenantId assigns the account to a tenant.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/connect")
    public ResponseEntity<ApiResponse<CarrierConnectResponse>> connectToCarrier(
            @Valid @RequestBody CarrierConnectRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<CarrierConnectResponse> response = carrierService.connectToCarrier(request, userDetails);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<CarrierStatusResponse>> getCarrierStatus(
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<CarrierStatusResponse> response = carrierService.getCarrierStatus(userDetails);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/disconnect")
    public ResponseEntity<ApiResponse<CarrierStatusResponse>> disconnectCarrier(
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<CarrierStatusResponse> response = carrierService.disconnectCarrier(userDetails);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<CarrierConnectResponse>> refreshToken(
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<CarrierConnectResponse> response = carrierService.refreshCarrierToken(userDetails);
        return ResponseEntity.status(response.getCode()).body(response);
    }

}
