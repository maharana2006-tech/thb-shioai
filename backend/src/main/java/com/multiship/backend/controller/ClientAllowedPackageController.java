package com.multiship.backend.controller;

import com.multiship.backend.dto.AllowPackageRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedPackageDTO;
import com.multiship.backend.service.ClientAllowedPackageService;
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
 * Per-client allowlist of {@link com.multiship.backend.model.PackagePreset}s.
 * Same shape as the allowed-services endpoints. Label generation refuses any
 * package not on this list.
 */
@Tag(name = "Client Allowed Packages", description = "Per-client package allowlist + default")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/packages")
@RequiredArgsConstructor
public class ClientAllowedPackageController {

    private final ClientAllowedPackageService service;

    @Operation(summary = "List packages allowed for a client (default first)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ClientAllowedPackageDTO>>> list(@PathVariable String clientCode) {
        ApiResponse<List<ClientAllowedPackageDTO>> response = service.listForClient(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Add a package to the client's allowlist",
            description = "409 ALLOWLIST_ALREADY_EXISTS on duplicates; first allow auto-defaults regardless of makeDefault.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientAllowedPackageDTO>> allow(
            @PathVariable String clientCode,
            @Valid @RequestBody AllowPackageRequest request) {
        ApiResponse<ClientAllowedPackageDTO> response = service.allow(clientCode, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Remove a package from the client's allowlist",
            description = "Removing the default auto-promotes the oldest remaining entry.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{presetId}")
    public ResponseEntity<ApiResponse<Void>> remove(
            @PathVariable String clientCode,
            @PathVariable Long presetId) {
        ApiResponse<Void> response = service.remove(clientCode, presetId);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Set the client's default package")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{presetId}/default")
    public ResponseEntity<ApiResponse<ClientAllowedPackageDTO>> setDefault(
            @PathVariable String clientCode,
            @PathVariable Long presetId) {
        ApiResponse<ClientAllowedPackageDTO> response = service.setDefault(clientCode, presetId);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
