package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.AttachWarehouseRequest;
import com.multiship.backend.dto.ClientWarehouseDTO;
import com.multiship.backend.dto.WarehouseSelectionRequest;
import com.multiship.backend.dto.WarehouseSelectionResult;
import com.multiship.backend.service.ClientWarehouseService;
import com.multiship.backend.service.warehouse.WarehouseSelector;
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
 * Client ↔ Warehouse assignments. Lists the warehouses a client can ship
 * from, and manages the per-client default that the shipment resolution
 * service picks when a shipment call doesn't name one.
 */
@Tag(name = "Client Warehouses", description = "Per-client warehouse assignments + default")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/warehouses")
@RequiredArgsConstructor
public class ClientWarehouseController {

    private final ClientWarehouseService service;
    private final WarehouseSelector warehouseSelector;

    @Operation(summary = "List warehouses attached to a client (default first)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ClientWarehouseDTO>>> list(@PathVariable String clientCode) {
        ApiResponse<List<ClientWarehouseDTO>> response = service.listForClient(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Attach a warehouse to the client",
            description = "409 WAREHOUSE_ALREADY_ATTACHED when the link already exists; 403 WAREHOUSE_ATTACH_FORBIDDEN for CLIENT-owned warehouses not owned by this client. Setting makeDefault=true (or attaching the first warehouse) clears any existing default.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientWarehouseDTO>> attach(
            @PathVariable String clientCode,
            @Valid @RequestBody AttachWarehouseRequest request) {
        ApiResponse<ClientWarehouseDTO> response = service.attach(clientCode, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Detach a warehouse from the client",
            description = "If the detached row was the default, the oldest remaining link is promoted so the client always has a default.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @DeleteMapping("/{warehouseCode}")
    public ResponseEntity<ApiResponse<Void>> detach(
            @PathVariable String clientCode,
            @PathVariable String warehouseCode) {
        ApiResponse<Void> response = service.detach(clientCode, warehouseCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Set the client's default warehouse",
            description = "Idempotent — clears the previous default and marks the named link as default.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/{warehouseCode}/default")
    public ResponseEntity<ApiResponse<ClientWarehouseDTO>> setDefault(
            @PathVariable String clientCode,
            @PathVariable String warehouseCode) {
        ApiResponse<ClientWarehouseDTO> response = service.setDefault(clientCode, warehouseCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    // ===== G3 — warehouse distance/postal selector =====

    @Operation(summary = "Preview which attached warehouse would fulfil this destination",
            description = "Scores every attached warehouse (same-country > postal-prefix within-country > any) and returns the winner plus a per-candidate trace so a dry-run UI can show why. Read-only — does not commit anything.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/select-nearest")
    public ResponseEntity<ApiResponse<WarehouseSelectionResult>> selectNearest(
            @PathVariable String clientCode,
            @RequestBody WarehouseSelectionRequest request) {
        WarehouseSelectionResult result = warehouseSelector.selectNearest(
                clientCode,
                request == null ? null : request.getDestCountry(),
                request == null ? null : request.getDestPostal());
        return ResponseEntity.ok(ApiResponse.<WarehouseSelectionResult>builder()
                .status("success").code(200)
                .message("NONE".equals(result.getMatchReason())
                        ? "Client has no attached warehouses."
                        : "Nearest warehouse resolved by " + result.getMatchReason() + ".")
                .data(result)
                .build());
    }
}
