package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelTemplateDTO;
import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.service.LabelTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Sprint 42 — CRUD for tenant-branded label templates. Powers the
 * packing slip that ships inside the parcel; the carrier's shipping
 * label itself is not customisable (carrier-mandated format).
 */
@Tag(name = "Label templates",
        description = "Tenant-branded packing slip / label templates (Sprint 42)")
@RestController
@RequestMapping("/api/v1/label-templates")
@RequiredArgsConstructor
public class LabelTemplateController {

    private final LabelTemplateService labelTemplateService;

    @Operation(summary = "Fetch the resolved template for a tenant + type",
            description = "Resolution order: tenant-scoped row, then platform " +
                    "default (tenantId=null). Always 200 — when neither exists, " +
                    "data is null so the UI renders a blank template. This was " +
                    "changed from 404 to keep the browser console clean on the " +
                    "expected 'no template configured yet' path.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') or @orderAccess.canViewTenant(authentication, #tenantId)")
    @GetMapping("/resolve")
    public ResponseEntity<ApiResponse<LabelTemplateDTO>> resolve(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "templateType", defaultValue = "PACKING_SLIP") String templateType) {
        Optional<LabelTemplate> found = labelTemplateService.resolve(tenantId, templateType);
        return ResponseEntity.ok(ApiResponse.<LabelTemplateDTO>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message(found.isPresent() ? "Template resolved" : "No template configured")
                .data(found.map(LabelTemplateDTO::from).orElse(null))
                .build());
    }

    @Operation(summary = "Fetch the tenant's own template (no fallback)",
            description = "Always 200 — data is null when the tenant hasn't " +
                    "configured a template yet. Same rationale as /resolve.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') or @orderAccess.canViewTenant(authentication, #tenantId)")
    @GetMapping("/tenant")
    public ResponseEntity<ApiResponse<LabelTemplateDTO>> forTenant(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "templateType", defaultValue = "PACKING_SLIP") String templateType) {
        Optional<LabelTemplate> found = labelTemplateService.findForTenant(tenantId, templateType);
        return ResponseEntity.ok(ApiResponse.<LabelTemplateDTO>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message(found.isPresent() ? "Template loaded" : "Tenant has no template of this type")
                .data(found.map(LabelTemplateDTO::from).orElse(null))
                .build());
    }

    @Operation(summary = "Upsert a label template",
            description = "Body id null → create; id set → update. " +
                    "One row per (tenantId, templateType).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') or @orderAccess.canViewTenant(authentication, #body.tenantId)")
    @PostMapping
    public ResponseEntity<ApiResponse<LabelTemplateDTO>> save(@RequestBody LabelTemplateDTO body) {
        LabelTemplate saved = labelTemplateService.save(body.toEntity());
        HttpStatus code = body.getId() == null ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(code).body(ApiResponse.<LabelTemplateDTO>builder()
                .status("success")
                .code(code.value())
                .message("Template saved")
                .data(LabelTemplateDTO.from(saved))
                .build());
    }

    @Operation(summary = "Delete a label template by id")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        labelTemplateService.delete(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message("Template deleted")
                .build());
    }
}
