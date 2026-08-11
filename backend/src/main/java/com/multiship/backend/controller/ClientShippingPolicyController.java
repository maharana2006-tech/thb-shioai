package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientShippingPolicyDTO;
import com.multiship.backend.dto.UpdateClientPolicyRequest;
import com.multiship.backend.service.ClientShippingPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-client rate-shopping strategy + dispatch cutoff. Absent row (never
 * PUT) implies platform defaults: CHEAPEST rate strategy, no cutoff. The
 * cutoff is a soft flag — the label call still succeeds after it, but the
 * response carries dispatchNextBusinessDay=true.
 */
@Tag(name = "Client Shipping Policy", description = "Per-client rate strategy + dispatch cutoff")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/policy")
@RequiredArgsConstructor
public class ClientShippingPolicyController {

    private final ClientShippingPolicyService service;

    @Operation(summary = "Get the client's shipping policy",
            description = "Returns the synthetic default (CHEAPEST, no cutoff) when nothing is stored.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping
    public ResponseEntity<ApiResponse<ClientShippingPolicyDTO>> get(@PathVariable String clientCode) {
        ApiResponse<ClientShippingPolicyDTO> response = service.get(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Update the client's shipping policy",
            description = "rateStrategy=FIXED requires fixedServiceId pointing to a service on the client's allowlist (400 POLICY_FIXED_SERVICE_REQUIRED otherwise).")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<ApiResponse<ClientShippingPolicyDTO>> update(
            @PathVariable String clientCode,
            @Valid @RequestBody UpdateClientPolicyRequest request) {
        ApiResponse<ClientShippingPolicyDTO> response = service.update(clientCode, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
