package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelTemplateDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.service.LabelTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** Whitelist of sortBy values accepted from the client — keeps the
     *  Sort DSL closed to entity properties, no free-form SQL. */
    private static final Set<String> SORTABLE = Set.of(
            "tenantId", "templateType", "updatedAt", "createdAt");

    @Operation(summary = "List label templates (operator settings page)",
            description = "Cross-tenant list for the settings UI. Filters: search matches " +
                    "tenant id (case-insensitive contains), templateType exact, hasLogo true/false. " +
                    "Operator-only — TENANT users don't see this endpoint (their template lives " +
                    "under /resolve). Sort: tenantId | templateType | updatedAt | createdAt.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponseDTO<LabelTemplateDTO>>> list(
            @Parameter(description = "Case-insensitive substring match on tenant id")
            @RequestParam(required = false) String search,
            @Parameter(description = "PACKING_SLIP | RETURN_COVER | COMMERCIAL_INVOICE")
            @RequestParam(required = false) String templateType,
            @Parameter(description = "true = only templates with a logo; false = only without; omit = both")
            @RequestParam(required = false) Boolean hasLogo,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        String safeSortBy = SORTABLE.contains(sortBy) ? sortBy : "updatedAt";
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDirection)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);

        Page<LabelTemplate> result = labelTemplateService.list(
                search, templateType, hasLogo,
                PageRequest.of(safePage, safeSize, Sort.by(dir, safeSortBy)));

        List<LabelTemplateDTO> content = result.getContent().stream()
                .map(LabelTemplateDTO::summary)
                .toList();
        PageResponseDTO<LabelTemplateDTO> body = PageResponseDTO.of(
                content, result.getNumber(), result.getSize(), result.getTotalElements(),
                safeSortBy, dir.name());

        return ResponseEntity.ok(ApiResponse.<PageResponseDTO<LabelTemplateDTO>>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message(body.getTotalElements() + " template(s)")
                .data(body)
                .build());
    }

    @Operation(summary = "Fetch a specific template by id",
            description = "Used by the operator settings editor when opening " +
                    "/settings/label-templates/{id}. 404 when the id doesn't exist.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LabelTemplateDTO>> getById(@PathVariable Long id) {
        Optional<LabelTemplate> found = labelTemplateService.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.<LabelTemplateDTO>builder()
                            .status("error")
                            .code(HttpStatus.NOT_FOUND.value())
                            .message("Label template " + id + " was not found.")
                            .build());
        }
        return ResponseEntity.ok(ApiResponse.<LabelTemplateDTO>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message("Template loaded")
                .data(LabelTemplateDTO.from(found.get()))
                .build());
    }

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
