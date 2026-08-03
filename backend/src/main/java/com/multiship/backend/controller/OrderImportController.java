package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.service.OrderImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Sprint 40 — CSV / XLSX order import endpoints.
 * <ul>
 *   <li>{@code POST /api/v1/orders/import/preview} — multipart upload,
 *       returns parsed rows + per-row validation status.</li>
 *   <li>{@code POST /api/v1/orders/import/commit} — client sends the
 *       (possibly edited) rows back; server persists valid rows.</li>
 *   <li>{@code GET /api/v1/orders/import/template.csv} — downloadable
 *       header + sample row so operators know the schema.</li>
 * </ul>
 */
@Tag(name = "Order import", description = "CSV / XLSX order upload (Sprint 40)")
@RestController
@RequestMapping("/api/v1/orders/import")
@RequiredArgsConstructor
public class OrderImportController {

    private final OrderImportService orderImportService;

    @Operation(summary = "Preview a CSV / XLSX upload",
            description = "Parses the file into a preview list, one entry per row, with per-row " +
                    "validation. The client renders the preview and lets the operator edit / discard " +
                    "bad rows before hitting the commit endpoint.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OrderImportPreviewDTO>> preview(
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        ApiResponse<OrderImportPreviewDTO> response = orderImportService.preview(
                file.getOriginalFilename(), file.getInputStream());
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Commit previewed rows",
            description = "Validates rows one last time (client may have edited them) and reports " +
                    "how many would be persisted. Sprint 40 MVP: reports only — persistence follow-up " +
                    "wires each valid row through the manual-shipment path.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/commit")
    public ResponseEntity<ApiResponse<OrderImportPreviewDTO>> commit(
            @RequestBody List<OrderImportRowDTO> rows,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        ApiResponse<OrderImportPreviewDTO> response = orderImportService.commit(rows, username);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Download the CSV template",
            description = "Public — the template is static schema (headers + one dummy row) " +
                    "and downloads via a browser <a href> that carries no Authorization header.")
    @GetMapping(value = "/template.csv", produces = "text/csv")
    public ResponseEntity<byte[]> template() {
        byte[] csv = orderImportService.csvTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"order-import-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @Operation(summary = "Download the XLSX template (data-validation dropdowns + samples)",
            description = "Sprint 48 — richer template with dropdowns, sample multi-row order, " +
                    "and an instructions sheet. When `accountId` is supplied the sample rows " +
                    "prefill accountNumber + carrierCode and the serviceType / packageType " +
                    "dropdowns narrow to that carrier's options only. Requires authentication " +
                    "because accountId resolves against private account data.")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/template.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> templateXlsx(
            @io.swagger.v3.oas.annotations.Parameter(description = "Optional carrier account id to scope the template to.")
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long accountId) {
        byte[] xlsx = orderImportService.xlsxTemplate(accountId);
        String filenameSuffix = accountId == null ? "generic" : ("account-" + accountId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"order-import-template-" + filenameSuffix + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
