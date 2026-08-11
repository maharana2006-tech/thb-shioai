package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.service.ClientCustomsProfileService;
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

/** Per-client importer + broker profiles, each covering a set of destination countries. */
@Tag(name = "Customs", description = "Importer/Broker profiles per client (each covers a set of destination countries)")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/customs-profiles")
@RequiredArgsConstructor
public class ClientCustomsProfileController {

    private final ClientCustomsProfileService service;

    @Operation(summary = "List a client's importer/broker profiles")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ClientCustomsProfileDTO>>> list(@PathVariable String clientCode) {
        ApiResponse<List<ClientCustomsProfileDTO>> r = service.list(clientCode);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Get one profile by id")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> get(
            @PathVariable String clientCode, @PathVariable Long id) {
        ApiResponse<ClientCustomsProfileDTO> r = service.get(clientCode, id);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Create a new importer/broker profile for a set of destination countries")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> create(
            @PathVariable String clientCode, @Valid @RequestBody ClientCustomsProfileDTO request) {
        request.setId(null);
        ApiResponse<ClientCustomsProfileDTO> r = service.upsert(clientCode, request);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Update an existing importer/broker profile")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> update(
            @PathVariable String clientCode, @PathVariable Long id,
            @Valid @RequestBody ClientCustomsProfileDTO request) {
        request.setId(id);
        ApiResponse<ClientCustomsProfileDTO> r = service.upsert(clientCode, request);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Delete a client's importer/broker profile by id")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String clientCode, @PathVariable Long id) {
        ApiResponse<Void> r = service.delete(clientCode, id);
        return ResponseEntity.status(r.getCode()).body(r);
    }
}
