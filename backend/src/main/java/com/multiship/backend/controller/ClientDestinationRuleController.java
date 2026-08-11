package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientDestinationRulesDTO;
import com.multiship.backend.dto.ReplaceDestinationRulesRequest;
import com.multiship.backend.service.ClientDestinationRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-client ship-to restrictions. A client is configured as either an
 * ALLOW list (only listed countries permitted) or a DENY list (listed
 * countries blocked, everything else fine). Mode is shared across all rows
 * for one client; the PUT endpoint enforces that by replacing atomically.
 */
@Tag(name = "Client Destination Rules", description = "Per-client ship-to whitelist / blacklist")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/destinations")
@RequiredArgsConstructor
public class ClientDestinationRuleController {

    private final ClientDestinationRuleService service;

    @Operation(summary = "Get the client's ship-to rules",
            description = "mode is null + countries=[] means no restriction (ship anywhere).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping
    public ResponseEntity<ApiResponse<ClientDestinationRulesDTO>> get(@PathVariable String clientCode) {
        ApiResponse<ClientDestinationRulesDTO> response = service.get(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Replace the client's ship-to rules",
            description = "Atomic replace: clears the current set and inserts the new one. Duplicates are collapsed; non-ISO-2 codes are dropped.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<ApiResponse<ClientDestinationRulesDTO>> replace(
            @PathVariable String clientCode,
            @Valid @RequestBody ReplaceDestinationRulesRequest request) {
        ApiResponse<ClientDestinationRulesDTO> response = service.replace(clientCode, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Clear every ship-to rule for the client (returns to unrestricted)")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clear(@PathVariable String clientCode) {
        ApiResponse<Void> response = service.clear(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
