package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.WarehouseDTO;
import com.multiship.backend.dto.WarehouseListFilters;
import com.multiship.backend.dto.WarehouseUpsertRequest;
import com.multiship.backend.service.WarehouseService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Warehouses — first-class ship-from locations shared across clients in a
 * 3PL setup. A warehouse is either PLATFORM-owned (attachable to any client)
 * or CLIENT-owned (attachable only to its owning client). The client↔warehouse
 * link and per-client default live on the {@code /clients/{code}/warehouses}
 * endpoints (see ClientWarehouseController).
 */
@Tag(name = "Warehouses", description = "Ship-from locations attachable to clients")
@RestController
@RequestMapping("/api/v1/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @Operation(summary = "List warehouses",
            description = "Paginated + filtered by owner type, owning client, or active. Search matches code, name, or city.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and (#ownerClientCode == null or @accessScope.canAccessTenant(authentication, #ownerClientCode))")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponseDTO<WarehouseDTO>>> listWarehouses(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) String ownerClientCode,
            @RequestParam(required = false) String active,
            @RequestParam(defaultValue = "code") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection,
            @RequestParam(defaultValue = "0") int page,
            // Sprint 51 AC-L1 — use PaginationDefaults for consistent size handling.
            @RequestParam(defaultValue = com.multiship.backend.common.PaginationDefaults.DEFAULT_SIZE_STR) int size) {
        WarehouseListFilters filters = WarehouseListFilters.builder()
                .search(search).ownerType(ownerType).ownerClientCode(ownerClientCode).active(active)
                .sortBy(sortBy).sortDirection(sortDirection).page(page)
                .size(com.multiship.backend.common.PaginationDefaults.clamp(size))
                .build();
        ApiResponse<PageResponseDTO<WarehouseDTO>> response = warehouseService.listWarehouses(filters);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Get one warehouse by code")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{code}")
    public ResponseEntity<ApiResponse<WarehouseDTO>> getWarehouse(@PathVariable String code) {
        ApiResponse<WarehouseDTO> response = warehouseService.getWarehouse(code);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Create a warehouse",
            description = "Ops-level action — accepts ADMIN or USER. 409 WAREHOUSE_CODE_TAKEN if the code exists; 400 WAREHOUSE_OWNER_INVALID if PLATFORM/CLIENT owner fields are inconsistent.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<WarehouseDTO>> createWarehouse(@Valid @RequestBody WarehouseUpsertRequest request) {
        ApiResponse<WarehouseDTO> response = warehouseService.createWarehouse(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Update a warehouse",
            description = "Ops-level action — accepts ADMIN or USER. The code is immutable (linkage key to client_warehouse and shipvia_service_mapping).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/{code}")
    public ResponseEntity<ApiResponse<WarehouseDTO>> updateWarehouse(
            @PathVariable String code,
            @Valid @RequestBody WarehouseUpsertRequest request) {
        ApiResponse<WarehouseDTO> response = warehouseService.updateWarehouse(code, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Toggle active flag",
            description = "Ops-level action — accepts ADMIN or USER.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{code}/toggle-active")
    public ResponseEntity<ApiResponse<WarehouseDTO>> toggleActive(@PathVariable String code) {
        ApiResponse<WarehouseDTO> response = warehouseService.toggleActive(code);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Delete a warehouse", description = "Also detaches every client link. Ship-method rules referencing the deleted warehouse fall back to any-warehouse resolution.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{code}")
    public ResponseEntity<ApiResponse<Void>> deleteWarehouse(@PathVariable String code) {
        ApiResponse<Void> response = warehouseService.deleteWarehouse(code);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
