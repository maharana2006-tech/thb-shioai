package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CustomFieldDefinitionDTO;
import com.multiship.backend.model.CustomFieldDefinition;
import com.multiship.backend.service.CustomFieldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sprint 43 — CRUD for tenant-scoped custom field definitions plus
 * bulk value upsert on orders. Definitions live under
 * {@code /api/v1/custom-fields}; per-order values under
 * {@code /api/v1/orders/{orderNo}/custom-fields}.
 */
@Tag(name = "Custom fields",
        description = "Tenant-defined custom fields on orders (Sprint 43)")
@RestController
@RequiredArgsConstructor
public class CustomFieldController {

    private final CustomFieldService customFieldService;

    // ===== Definitions =====

    @Operation(summary = "List every definition for a tenant (active + inactive)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/api/v1/custom-fields")
    public ResponseEntity<ApiResponse<List<CustomFieldDefinitionDTO>>> list(
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        List<CustomFieldDefinitionDTO> data = customFieldService.listAllForTenant(tenantId).stream()
                .map(CustomFieldDefinitionDTO::from).toList();
        return ok(data, "Definitions loaded");
    }

    @Operation(summary = "List definitions applicable to a tenant's order form (active only)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/api/v1/custom-fields/applicable")
    public ResponseEntity<ApiResponse<List<CustomFieldDefinitionDTO>>> applicable(
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        List<CustomFieldDefinitionDTO> data = customFieldService.listApplicable(tenantId).stream()
                .map(CustomFieldDefinitionDTO::from).toList();
        return ok(data, "Applicable definitions loaded");
    }

    @Operation(summary = "Create or update a definition",
            description = "Null id creates; set id updates. fieldKey must be unique per tenant.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/api/v1/custom-fields")
    public ResponseEntity<ApiResponse<CustomFieldDefinitionDTO>> save(
            @RequestBody CustomFieldDefinitionDTO body) {
        try {
            CustomFieldDefinition saved = customFieldService.saveDefinition(body.toEntity());
            HttpStatus code = body.getId() == null ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(code).body(ApiResponse.<CustomFieldDefinitionDTO>builder()
                    .status("success")
                    .code(code.value())
                    .message("Definition saved")
                    .data(CustomFieldDefinitionDTO.from(saved))
                    .build());
        } catch (IllegalArgumentException validation) {
            return badRequest(validation.getMessage(), "VALIDATION_FAILED");
        }
    }

    @Operation(summary = "Delete a definition by id")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/api/v1/custom-fields/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        customFieldService.deleteDefinition(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status("success").code(HttpStatus.OK.value())
                .message("Definition deleted").build());
    }

    // ===== Values =====

    @Operation(summary = "Load the custom-field values attached to an order")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping("/api/v1/orders/{orderNo}/custom-fields")
    public ResponseEntity<ApiResponse<Map<String, String>>> values(@PathVariable Integer orderNo) {
        return ok(customFieldService.loadValues(orderNo), "Values loaded");
    }

    @Operation(summary = "Upsert custom-field values on an order",
            description = "Body is a fieldKey→value map. Unknown keys or type mismatches return 400.")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @PostMapping("/api/v1/orders/{orderNo}/custom-fields")
    public ResponseEntity<ApiResponse<Map<String, String>>> upsert(
            @PathVariable Integer orderNo,
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody Map<String, String> values) {
        try {
            Map<String, String> saved = customFieldService.upsertValues(orderNo, tenantId, values);
            return ok(saved, "Values saved");
        } catch (IllegalArgumentException validation) {
            return badRequest(validation.getMessage(), "VALIDATION_FAILED");
        }
    }

    // ===== helpers =====

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value())
                .message(message).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> badRequest(String message, String errorCode) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode(errorCode).build());
    }
}
