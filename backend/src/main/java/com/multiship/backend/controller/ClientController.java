package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.ClientDTO;
import com.multiship.backend.dto.ClientUpsertRequest;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.service.ClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Client (customer) master data. Orders link to a client via their customer
 * code; the label-generation cascade consults the client's default carrier
 * account before the company default, and refuses to generate for orders
 * whose client is unregistered or inactive.
 */
@Tag(name = "Clients", description = "Client master data: profile, status, and linked carrier accounts")
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;

    @Operation(summary = "List clients", description = "Paginated; search matches code, name, or city.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponseDTO<ClientDTO>>> listClients(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String hasOrders,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "code") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        com.multiship.backend.dto.ClientListFilters filters = com.multiship.backend.dto.ClientListFilters.builder()
                .search(search).status(status).carrier(carrier).hasOrders(hasOrders)
                .code(code).name(name).city(city)
                .sortBy(sortBy).sortDirection(sortDirection).page(page).size(size)
                .build();
        ApiResponse<PageResponseDTO<ClientDTO>> response = clientService.listClients(filters);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Get one client with linked accounts and order count")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{clientCode}")
    public ResponseEntity<ApiResponse<ClientDTO>> getClient(@PathVariable String clientCode) {
        ApiResponse<ClientDTO> response = clientService.getClient(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Create a client",
            description = "409 errorCode CLIENT_CODE_TAKEN if the code is already registered.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientDTO>> createClient(@Valid @RequestBody ClientUpsertRequest request) {
        ApiResponse<ClientDTO> response = clientService.createClient(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Update a client", description = "The client code itself is immutable (it links orders and accounts).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/{clientCode}")
    public ResponseEntity<ApiResponse<ClientDTO>> updateClient(
            @PathVariable String clientCode,
            @Valid @RequestBody ClientUpsertRequest request) {
        ApiResponse<ClientDTO> response = clientService.updateClient(clientCode, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Toggle ACTIVE/INACTIVE with cascade",
            description = "Ops-level action — accepts ADMIN or USER. " +
                    "On DISABLE: refuses (409 CLIENT_HAS_ORDERS) if pending orders exist; " +
                    "otherwise cascades — deactivates carrier accounts (customerNo=X), " +
                    "client-owned warehouses, and drops attachment links. Snapshot goes to " +
                    "audit_log so ENABLE restores only these rows.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{clientCode}/toggle-active")
    public ResponseEntity<ApiResponse<ClientDTO>> toggleActive(@PathVariable String clientCode) {
        ApiResponse<ClientDTO> response = clientService.toggleActive(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Preview client-disable cascade",
            description = "Returns pending-order count (hard-block on disable when >0) plus " +
                    "counts of the associated rows a disable would deactivate. Read-only.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{clientCode}/cascade-preview")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ClientCascadePreviewDTO>> previewCascade(
            @PathVariable String clientCode) {
        ApiResponse<com.multiship.backend.dto.ClientCascadePreviewDTO> response =
                clientService.previewCascade(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Delete a client (ADMIN)",
            description = "409 errorCode CLIENT_HAS_ORDERS when orders reference the client — deactivate instead.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{clientCode}")
    public ResponseEntity<ApiResponse<Void>> deleteClient(@PathVariable String clientCode) {
        ApiResponse<Void> response = clientService.deleteClient(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "List the client's linked carrier accounts (client default first)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{clientCode}/carrier-accounts")
    public ResponseEntity<ApiResponse<List<CarrierAccountRefDTO>>> listClientAccounts(@PathVariable String clientCode) {
        ApiResponse<List<CarrierAccountRefDTO>> response = clientService.listClientAccounts(clientCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Export every filtered client as CSV",
            description = "Same filter/sort parameters as GET /clients; ignores page/size and streams the entire result set.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportClientsCsv(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String hasOrders,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "code") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        com.multiship.backend.dto.ClientListFilters filters = com.multiship.backend.dto.ClientListFilters.builder()
                .search(search).status(status).carrier(carrier).hasOrders(hasOrders)
                .code(code).name(name).city(city)
                .sortBy(sortBy).sortDirection(sortDirection)
                .page(0).size(1)
                .build();
        String csv = clientService.exportClientsCsv(filters);
        // Prepend a UTF-8 BOM so Excel opens the file in UTF-8 by default.
        byte[] body = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        String filename = "clients-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
