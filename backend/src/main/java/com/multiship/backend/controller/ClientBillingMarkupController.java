package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientBillingMarkupDTO;
import com.multiship.backend.dto.UpdateClientMarkupRequest;
import com.multiship.backend.service.ClientBillingMarkupService;
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
 * Per-client billing markup applied to the carrier rate at label time.
 * Absent row implies zero markup (USD). The kind + value are snapshotted
 * onto the shipment record so historical bills are stable.
 */
@Tag(name = "Client Billing Markup", description = "Per-client markup on top of carrier rates")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/markup")
@RequiredArgsConstructor
public class ClientBillingMarkupController {

    private final ClientBillingMarkupService service;

    @Operation(summary = "Get the client's billing markup",
            description = "Returns the synthetic default (PERCENT, 0.0000, USD) when nothing is stored.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping
    public ResponseEntity<ApiResponse<ClientBillingMarkupDTO>> get(@PathVariable String clientCode) {
        ApiResponse<ClientBillingMarkupDTO> response = service.get(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Update the client's billing markup",
            description = "400 MARKUP_INVALID when kind is not PERCENT|FLAT, value is negative, or currency is not 3 letters.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<ApiResponse<ClientBillingMarkupDTO>> update(
            @PathVariable String clientCode,
            @Valid @RequestBody UpdateClientMarkupRequest request) {
        ApiResponse<ClientBillingMarkupDTO> response = service.update(clientCode, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
