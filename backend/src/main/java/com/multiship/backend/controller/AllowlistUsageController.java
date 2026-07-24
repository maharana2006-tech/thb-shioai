package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedPackageDTO;
import com.multiship.backend.dto.ClientAllowedServiceDTO;
import com.multiship.backend.service.ClientAllowedPackageService;
import com.multiship.backend.service.ClientAllowedServiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cross-cutting "who uses what" reads for the settings catalog pages.
 * Returns flat lists of every client↔service and client↔package assignment;
 * the frontend groups them by service/preset id to render "Assigned to (n)"
 * columns and a click-through drawer of client codes.
 */
@Tag(name = "Allowlist Usage", description = "Cross-client allowlist usage for catalog pages")
@RestController
@RequestMapping("/api/v1/allowlist-usage")
@RequiredArgsConstructor
public class AllowlistUsageController {

    private final ClientAllowedServiceService serviceService;
    private final ClientAllowedPackageService packageService;

    @Operation(summary = "Every service-allowlist row across all clients")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/services")
    public ResponseEntity<ApiResponse<List<ClientAllowedServiceDTO>>> services() {
        ApiResponse<List<ClientAllowedServiceDTO>> r = serviceService.listAllAssignments();
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Every package-allowlist row across all clients")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/packages")
    public ResponseEntity<ApiResponse<List<ClientAllowedPackageDTO>>> packages() {
        ApiResponse<List<ClientAllowedPackageDTO>> r = packageService.listAllAssignments();
        return ResponseEntity.status(r.getCode()).body(r);
    }
}
