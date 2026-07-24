package com.multiship.backend.controller;

import com.multiship.backend.dto.AllowServiceRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedServiceDTO;
import com.multiship.backend.service.ClientAllowedServiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-client allowlist of carrier services. Nothing is visible to a client
 * until an admin adds a row here — that's the 3PL explicit-assignment model.
 * Label generation refuses any service not on this list.
 */
@Tag(name = "Client Allowed Services", description = "Per-client service allowlist + default")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/services")
@RequiredArgsConstructor
public class ClientAllowedServiceController {

    private final ClientAllowedServiceService service;

    @Operation(summary = "List services allowed for a client (default first)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ClientAllowedServiceDTO>>> list(@PathVariable String clientCode) {
        ApiResponse<List<ClientAllowedServiceDTO>> response = service.listForClient(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Add a service to the client's allowlist",
            description = "409 ALLOWLIST_ALREADY_EXISTS on duplicates; first allow auto-defaults regardless of makeDefault.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientAllowedServiceDTO>> allow(
            @PathVariable String clientCode,
            @Valid @RequestBody AllowServiceRequest request) {
        ApiResponse<ClientAllowedServiceDTO> response = service.allow(clientCode, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Remove a service from the client's allowlist",
            description = "Removing the default auto-promotes the oldest remaining entry.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{serviceId}")
    public ResponseEntity<ApiResponse<Void>> remove(
            @PathVariable String clientCode,
            @PathVariable Long serviceId) {
        ApiResponse<Void> response = service.remove(clientCode, serviceId);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Set the client's default service")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{serviceId}/default")
    public ResponseEntity<ApiResponse<ClientAllowedServiceDTO>> setDefault(
            @PathVariable String clientCode,
            @PathVariable Long serviceId) {
        ApiResponse<ClientAllowedServiceDTO> response = service.setDefault(clientCode, serviceId);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
