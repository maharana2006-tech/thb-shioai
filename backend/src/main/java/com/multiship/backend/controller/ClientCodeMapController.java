package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCodeMapDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UpsertClientCodeMapRequest;
import com.multiship.backend.service.ClientCodeMapService;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ERP↔platform code aliases per client. Four flavours share one path:
 * {@code /clients/{code}/code-maps/{kind}}. Order intake consumes these to
 * translate raw ERP codes to canonical platform values before writing the
 * order row.
 *
 * <p>Kind values (URL-lowercased): {@code shipvia | service | dest-country
 * | package}.
 */
@Tag(name = "Client Code Maps", description = "Per-client ERP↔platform code aliases")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/code-maps/{kind}")
@RequiredArgsConstructor
public class ClientCodeMapController {

    private final ClientCodeMapService service;

    @Operation(summary = "List every alias of this kind for the client")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ClientCodeMapDTO>>> list(
            @PathVariable String clientCode,
            @PathVariable String kind) {
        ClientCodeMapDTO.Kind parsed;
        try { parsed = parseKind(kind); }
        catch (IllegalArgumentException ex) { return badKind(kind); }
        ApiResponse<List<ClientCodeMapDTO>> response = service.list(clientCode, parsed);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Create or replace an alias (upsert by (client, erpCode))",
            description = "SHIPVIA / SERVICE / PACKAGE: send targetId; DEST_COUNTRY: send iso2.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<ClientCodeMapDTO>> upsert(
            @PathVariable String clientCode,
            @PathVariable String kind,
            @Valid @RequestBody UpsertClientCodeMapRequest request) {
        ClientCodeMapDTO.Kind parsed;
        try { parsed = parseKind(kind); }
        catch (IllegalArgumentException ex) { return badKind(kind); }
        ApiResponse<ClientCodeMapDTO> response = service.upsert(clientCode, parsed, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Remove a single alias row by id")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> remove(
            @PathVariable String clientCode,
            @PathVariable String kind,
            @PathVariable Long id) {
        ClientCodeMapDTO.Kind parsed;
        try { parsed = parseKind(kind); }
        catch (IllegalArgumentException ex) { return badKind(kind); }
        ApiResponse<Void> response = service.remove(clientCode, parsed, id);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /**
     * Audit B6 — unknown slug used to bubble up as IllegalArgumentException
     * from {@code Kind.valueOf(...)} and Spring served it as 500 with the
     * exception stack leaking into the response. Now shaped as a clean 400
     * VALIDATION_ERROR with the slug the caller sent + the valid options.
     */
    private static <T> ResponseEntity<ApiResponse<T>> badKind(String slug) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message("unknown code-map kind '" + slug + "' — expected one of "
                        + "shipvia / service / dest-country / package")
                .build());
    }

    /** URL-friendly slug → enum. "dest-country" → DEST_COUNTRY. */
    private static ClientCodeMapDTO.Kind parseKind(String slug) {
        if (slug == null) throw new IllegalArgumentException("kind is required");
        String enumForm = slug.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return ClientCodeMapDTO.Kind.valueOf(enumForm);
    }
}
