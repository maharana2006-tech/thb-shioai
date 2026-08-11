package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelTemplateDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.service.LabelTemplateService;
import com.multiship.backend.service.template.SampleShipmentContext;
import com.multiship.backend.service.template.TemplateHtmlRenderer;
import com.multiship.backend.service.template.TemplatePdfRenderer;
import com.multiship.backend.service.template.TemplateZplRenderer;
import org.springframework.http.MediaType;
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
            @Parameter(description = "'Y' = only with logo, 'N' = only without, omit = both. " +
                    "(String, not boolean — avoids the Postgres bytea-null-typing issue on " +
                    "Hibernate-bound nullable booleans.)")
            @RequestParam(required = false) String hasLogo,
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
    @PreAuthorize("(hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #tenantId)) or @orderAccess.canViewTenant(authentication, #tenantId)")
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
    @PreAuthorize("(hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #tenantId)) or @orderAccess.canViewTenant(authentication, #tenantId)")
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
    @PreAuthorize("(hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #body.tenantId)) or @orderAccess.canViewTenant(authentication, #body.tenantId)")
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

    /**
     * Phase 2a preview endpoint. Renders the given layout JSON to a
     * self-contained HTML document using {@link SampleShipmentContext} for
     * bindings. Powers the "Preview" panel in the drag-drop editor so the
     * operator sees blocks resolve against realistic data as they build.
     *
     * <p>Accepts the layout in the request body so unsaved edits can be
     * previewed without a round-trip through save+load. The response is
     * always 200 with an HTML payload; a bad JSON body renders an inline
     * placeholder (the frontend iframe never sees an error page).
     */
    @Operation(summary = "Render a template layout to HTML for preview",
            description = "Uses the built-in sample shipment context. Real per-shipment " +
                    "preview lands in Phase 2b when the Shipment-to-context adapter ships.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping(value = "/preview", produces = "text/html")
    public ResponseEntity<String> previewLayout(@RequestBody(required = false) PreviewRequest body) {
        String layoutJson = body == null ? null : body.getLayoutJson();
        String html = TemplateHtmlRenderer.render(layoutJson, SampleShipmentContext.sample());
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8").body(html);
    }

    /**
     * Phase 2b — same layout resolved to a PDF for download. The endpoint
     * is separate from {@code /preview} because browsers render inline
     * PDFs differently from raw HTML, and the frontend chooses the
     * MIME type via which URL it fetches. Payload is the same
     * PreviewRequest body so unsaved edits render without a save round-trip.
     */
    @Operation(summary = "Render a template layout to PDF for download",
            description = "Uses the built-in sample shipment context, same as /preview.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping(value = "/preview.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> previewLayoutPdf(@RequestBody(required = false) PreviewRequest body) {
        String layoutJson = body == null ? null : body.getLayoutJson();
        byte[] pdf = TemplatePdfRenderer.render(layoutJson, SampleShipmentContext.sample());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"template-preview.pdf\"")
                .body(pdf);
    }

    /**
     * Phase 2c — resolve the layout to ZPL II for direct printing on a
     * Zebra-compatible thermal head. Returns text/plain so the raw string
     * can be piped straight to a printer (via lpr / a raw socket / the
     * carrier SDK's "print raw" endpoint) or saved and inspected.
     *
     * <p>Query {@code ?dpi=203} (default) or {@code ?dpi=300} to target
     * different thermal head resolutions.
     */
    @Operation(summary = "Render a template layout to ZPL for a Zebra printer",
            description = "Uses the built-in sample shipment context. Content-Type is text/plain " +
                    "so the response body IS the raw ZPL — pipe it to the printer or save as .zpl.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping(value = "/preview.zpl", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> previewLayoutZpl(
            @RequestBody(required = false) PreviewRequest body,
            @RequestParam(name = "dpi", defaultValue = "203") int dpi) {
        String layoutJson = body == null ? null : body.getLayoutJson();
        // Clamp DPI to the two common thermal-head resolutions; other
        // values would still math correctly but almost certainly indicate
        // a client typo, so we normalise silently to 203.
        int safeDpi = (dpi == 300) ? 300 : 203;
        String zpl = TemplateZplRenderer.render(layoutJson,
                SampleShipmentContext.sample(), safeDpi);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header("Content-Disposition", "inline; filename=\"template-preview.zpl\"")
                .body(zpl);
    }

    /** Body shape for {@link #previewLayout} — thin wrapper so we can grow
     *  the endpoint later without breaking clients (e.g. add a target
     *  shipmentId for real-shipment preview in Phase 2b). */
    @lombok.Data
    public static class PreviewRequest {
        private String layoutJson;
    }
}
