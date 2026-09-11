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
                    "bad rows before hitting the commit endpoint. When `expectedAccountId` is " +
                    "supplied (i.e. the operator downloaded a scoped .xlsx template first), any " +
                    "row whose accountNumber diverges from that account's number gets a non-fatal " +
                    "warning.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OrderImportPreviewDTO>> preview(
            @RequestParam("file") MultipartFile file,
            @io.swagger.v3.oas.annotations.Parameter(description = "Optional — the account id the .xlsx template was scoped to. Rows whose accountNumber differs get a non-fatal warning.")
            @RequestParam(value = "expectedAccountId", required = false) Long expectedAccountId,
            @RequestParam(value = "allowDuplicate", required = false, defaultValue = "false") boolean allowDuplicate)
            throws java.io.IOException {
        ApiResponse<OrderImportPreviewDTO> response = orderImportService.preview(
                file.getOriginalFilename(), file.getInputStream(), expectedAccountId, allowDuplicate);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    // ── Staging: upload → validate → Save (2026-09-11 bulk-upload restructure) ──

    @Operation(summary = "Upload a CSV / XLSX into staging and validate it",
            description = "Parses and validates the file and parks it in staging. Nothing reaches Import history "
                    + "until Save, and Save writes only orders whose every row is valid.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping(value = "/staging", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.StagingUploadDTO>> stageUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "allowDuplicate", required = false, defaultValue = "false") boolean allowDuplicate,
            @AuthenticationPrincipal UserDetails userDetails) throws java.io.IOException {
        ApiResponse<com.multiship.backend.dto.StagingUploadDTO> r = orderImportService.stageUpload(
                file.getOriginalFilename(), file.getInputStream(), allowDuplicate, stagingUser(userDetails));
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Your uploads still waiting in staging (no rows)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/staging")
    public ResponseEntity<ApiResponse<java.util.List<com.multiship.backend.dto.StagingUploadDTO>>> listStaging(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.<java.util.List<com.multiship.backend.dto.StagingUploadDTO>>builder()
                .status("success").code(200)
                .data(orderImportService.listStaging(stagingUser(userDetails)))
                .build());
    }

    @Operation(summary = "A staged upload with its rows and per-order counts")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/staging/{id}")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.StagingUploadDTO>> getStaging(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<com.multiship.backend.dto.StagingUploadDTO> r = orderImportService.getStaging(id, stagingUser(userDetails));
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Edit one staged row; the whole upload is re-validated")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @org.springframework.web.bind.annotation.PutMapping("/staging/{id}/rows/{rowNumber}")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.StagingUploadDTO>> updateStagingRow(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.PathVariable int rowNumber,
            @RequestBody OrderImportRowDTO row,
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<com.multiship.backend.dto.StagingUploadDTO> r =
                orderImportService.updateStagingRow(id, rowNumber, row, stagingUser(userDetails));
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Save the fully valid orders of a staged upload to Import history")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/staging/{id}/save")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.StagingUploadDTO>> saveStaging(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @io.swagger.v3.oas.annotations.Parameter(description = "false = \"Ignore errors and save\" (valid orders only); true = \"Proceed with errors\" (every unsaved order, as a Draft while errors remain)")
            @RequestParam(value = "includeErrors", required = false, defaultValue = "false") boolean includeErrors,
            @io.swagger.v3.oas.annotations.Parameter(description = "true = save orders that are already in Import history anyway (409 otherwise)")
            @RequestParam(value = "allowDuplicate", required = false, defaultValue = "false") boolean allowDuplicate,
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<com.multiship.backend.dto.StagingUploadDTO> r =
                orderImportService.saveStaging(id, stagingUser(userDetails), includeErrors, allowDuplicate);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Discard a staged upload (orders already saved stay in Import history)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @org.springframework.web.bind.annotation.DeleteMapping("/staging/{id}")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.StagingUploadDTO>> discardStaging(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        ApiResponse<com.multiship.backend.dto.StagingUploadDTO> r = orderImportService.discardStaging(id, stagingUser(userDetails));
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Download the orders with errors (template columns + an errors column)",
            description = "format=csv|xlsx; defaults to the uploaded file's type. 404 when no order has errors.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/staging/{id}/errors")
    public ResponseEntity<byte[]> stagingErrors(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @RequestParam(value = "format", required = false) String format,
            @AuthenticationPrincipal UserDetails userDetails) {
        OrderImportService.StagingErrorFile f = orderImportService.stagingErrorFile(id, format, stagingUser(userDetails));
        if (f == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + f.fileName().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.bytes());
    }

    private static String stagingUser(UserDetails userDetails) {
        return userDetails == null ? "unknown" : userDetails.getUsername();
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

    @Operation(summary = "Save previewed rows to Data History (no labels)",
            description = "Persists the imported rows as a data record for the Data History page. " +
                    "Unlike /commit this does NOT generate carrier labels.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/save")
    public ResponseEntity<ApiResponse<OrderImportPreviewDTO>> save(
            @RequestBody List<OrderImportRowDTO> rows,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String fileName,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean draft,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean allowDuplicate,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        ApiResponse<OrderImportPreviewDTO> response = orderImportService.save(rows, username, fileName, draft, allowDuplicate);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "List saved imports (Data History)",
            description = "Live imports by default; pass deleted=true for the Trash view.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<java.util.List<com.multiship.backend.dto.ImportBatchDTO>>> history(
            @RequestParam(required = false, defaultValue = "false") boolean deleted) {
        java.util.List<com.multiship.backend.dto.ImportBatchDTO> data =
                deleted ? orderImportService.deletedHistory() : orderImportService.history();
        return ResponseEntity.ok(ApiResponse.<java.util.List<com.multiship.backend.dto.ImportBatchDTO>>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message(deleted ? "Trash loaded." : "Import history loaded.")
                .data(data)
                .build());
    }

    @Operation(summary = "Soft-delete an import batch (move to Trash)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @org.springframework.web.bind.annotation.DeleteMapping("/history/{id}")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ImportBatchDTO>> deleteBatch(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        com.multiship.backend.dto.ImportBatchDTO dto = orderImportService.softDeleteBatch(id, username);
        if (dto == null) {
            return ResponseEntity.status(404).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(404).timestamp(java.time.LocalDateTime.now())
                    .message("Import not found.").build());
        }
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message("Import moved to Trash.").data(dto).build());
    }

    @Operation(summary = "Restore a soft-deleted import batch from Trash")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/history/{id}/restore")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ImportBatchDTO>> restoreBatch(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        com.multiship.backend.dto.ImportBatchDTO dto = orderImportService.restoreBatch(id);
        if (dto == null) {
            return ResponseEntity.status(404).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(404).timestamp(java.time.LocalDateTime.now())
                    .message("Import not found.").build());
        }
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message("Import restored.").data(dto).build());
    }

    @Operation(summary = "Set a batch's bill-to account mode (AUTO | PLATFORM)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @org.springframework.web.bind.annotation.PutMapping("/history/{id}/billing-mode")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ImportBatchDTO>> setBillingMode(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @RequestParam String mode,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        com.multiship.backend.dto.ImportBatchDTO dto = orderImportService.setBillingMode(id, mode, username);
        if (dto == null) {
            return ResponseEntity.status(404).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(404).timestamp(java.time.LocalDateTime.now())
                    .message("Import not found.").build());
        }
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message("Billing mode updated.").data(dto).build());
    }

    @Operation(summary = "Empty the Trash — permanently delete all soft-deleted imports",
            description = "Irreversible. Hard-deletes every batch currently in Trash for the caller's tenant.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @org.springframework.web.bind.annotation.DeleteMapping("/history/trash")
    public ResponseEntity<ApiResponse<Integer>> emptyTrash(
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        int purged = orderImportService.purgeTrash(username);
        return ResponseEntity.ok(ApiResponse.<Integer>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message(purged == 1 ? "1 import permanently deleted."
                        : purged + " imports permanently deleted.")
                .data(purged).build());
    }

    @Operation(summary = "One saved import with its rows")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/history/{id}")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ImportBatchDTO>> historyDetail(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        com.multiship.backend.dto.ImportBatchDTO dto = orderImportService.historyDetail(id);
        if (dto == null) {
            return ResponseEntity.status(404).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(404).timestamp(java.time.LocalDateTime.now())
                    .message("Import not found.")
                    .build());
        }
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message("Import loaded.")
                .data(dto)
                .build());
    }

    @Operation(summary = "Generate carrier labels for a saved import batch",
            description = "Advances the batch status INITIATE → IN_PROGRESS → COMPLETE / " +
                    "PARTIAL_COMPLETE as it generates a label per saved row.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/history/{id}/generate")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ImportBatchDTO>> generateForBatch(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam(name = "onlyFailed", defaultValue = "false") boolean onlyFailed,
            @RequestParam(required = false, defaultValue = "false") boolean usePlatformAccount,
            @RequestParam(required = false, defaultValue = "false") boolean allowDuplicate,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        // Sprint 55 audit #302 F3.2 — onlyFailed=true skips rows already
        // marked GENERATED to prevent duplicate carrier calls + billing
        // on retry. FE defaults to true when isRetry (see DataHistoryPage).
        // usePlatformAccount=true forces the platform (house) account.
        // allowDuplicate=true confirms re-shipping orders flagged as already labelled (409 otherwise).
        com.multiship.backend.dto.ImportBatchDTO dto;
        try {
            dto = orderImportService.generateLabelsForBatch(id, username, onlyFailed, usePlatformAccount, allowDuplicate);
        } catch (com.multiship.backend.service.OrderImportServiceImpl.DuplicateShipmentException dup) {
            return ResponseEntity.status(409).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(409).timestamp(java.time.LocalDateTime.now())
                    .message(dup.getMessage()).build());
        } catch (com.multiship.backend.service.OrderImportServiceImpl.ConcurrentBatchGenerationException race) {
            // Import I-11 — a second Generate click landed on a batch
            // already in IN_PROGRESS. Refuse rather than silently produce
            // duplicate paid shipments. Frontend shows the message inline
            // and re-enables the button once the batch reaches a terminal
            // state.
            return ResponseEntity.status(409).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(409).timestamp(java.time.LocalDateTime.now())
                    .errorCode(com.multiship.backend.dto.ErrorCode.IMPORT_BATCH_ALREADY_GENERATING.name())
                    .message(race.getMessage())
                    .build());
        }
        if (dto == null) {
            return ResponseEntity.status(404).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(404).timestamp(java.time.LocalDateTime.now())
                    .message("Import not found.")
                    .build());
        }
        // Count ORDERS, not rows: item-line rows of one order share its label, so
        // "97 of 126 label(s)" read as 29 failures when 8 orders had failed.
        java.util.Map<String, Boolean> orderDone = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> orderHasErrors = new java.util.HashMap<>();
        if (dto.getRows() != null) {
            int idx = 0;
            for (com.multiship.backend.dto.OrderImportRowDTO r : dto.getRows()) {
                idx++;
                // A row without an orderRef is its own order; key it by position,
                // which is always unique (rowNumber can be unset).
                String key = org.springframework.util.StringUtils.hasText(r.getOrderRef())
                        ? r.getOrderRef().trim().toUpperCase(java.util.Locale.ROOT) : "#pos" + idx;
                boolean g = "GENERATED".equalsIgnoreCase(r.getGeneratedStatus());
                orderDone.merge(key, g, Boolean::logicalOr);
                orderHasErrors.merge(key, r.getErrors() != null && !r.getErrors().isEmpty(), Boolean::logicalOr);
            }
        }
        long gen = orderDone.values().stream().filter(Boolean::booleanValue).count();
        // Orders still carrying errors can't be labelled yet — keep them out of
        // the "N of M" and say how many wait for fixes.
        long needFixes = orderDone.entrySet().stream()
                .filter(e -> !e.getValue() && Boolean.TRUE.equals(orderHasErrors.get(e.getKey()))).count();
        long totalRows = orderDone.size() - needFixes;
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message(gen + " of " + totalRows + (totalRows == 1 ? " order" : " orders") + " labelled"
                        + (needFixes > 0 ? " · " + needFixes + (needFixes == 1 ? " needs" : " need") + " fixes" : "")
                        + " · " + statusLabel(dto.getStatus()))
                .data(dto)
                .build());
    }

    @Operation(summary = "Cancel an in-flight import label-generation run",
            description = "Cooperative cancellation: the flag is set immediately; worker groups stop " +
                    "picking up new orders after the flag is set. Already-in-flight carrier calls run to " +
                    "completion (we cannot interrupt a paid label mid-request without leaking it). " +
                    "404 if the batch is unknown, 409 BULK_JOB_ALREADY_TERMINAL if the batch is already " +
                    "COMPLETE/PARTIAL_COMPLETE/FAILED/CANCELLED. Tenant-scoped: a USER cannot cancel " +
                    "another tenant's batch.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @org.springframework.web.bind.annotation.DeleteMapping("/history/{id}/generate")
    public ResponseEntity<ApiResponse<String>> cancelGeneration(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        ApiResponse<String> response = orderImportService.cancelGeneration(id);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /** Human-friendly batch status for API messages. */
    private static String statusLabel(String status) {
        if (status == null) return "";
        switch (status.toUpperCase()) {
            case "COMPLETE": return "Complete";
            case "PARTIAL_COMPLETE": return "Partial complete";
            case "FAILED": return "Failed";
            case "IN_PROGRESS": return "In progress";
            case "INITIATE": return "Saved · not generated";
            case "CANCELLED": return "Cancelled";
            default: return status;
        }
    }

    @Operation(summary = "Generate a carrier label for one row of a saved batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/history/{id}/generate/{rowNumber}")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ImportBatchDTO>> generateForRow(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.PathVariable int rowNumber,
            @RequestParam(required = false, defaultValue = "false") boolean allowDuplicate,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        com.multiship.backend.dto.ImportBatchDTO dto;
        try {
            dto = orderImportService.generateLabelForRow(id, rowNumber, username, allowDuplicate);
        } catch (com.multiship.backend.service.OrderImportServiceImpl.DuplicateShipmentException dup) {
            return ResponseEntity.status(409).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(409).timestamp(java.time.LocalDateTime.now())
                    .message(dup.getMessage()).build());
        }
        if (dto == null) {
            return ResponseEntity.status(404).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(404).timestamp(java.time.LocalDateTime.now())
                    .message("Import not found.")
                    .build());
        }
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message("Label generation attempted for row " + rowNumber + " · status " + dto.getStatus())
                .data(dto)
                .build());
    }

    @Operation(summary = "Live label-generation progress for a batch",
            description = "Returns { done, total, running } while a generate/retry is in flight, so the "
                    + "UI can show a real X-of-N progress bar. Cheap to poll (in-memory, no DB round-trip); "
                    + "running=false with done=total=0 means nothing is generating for this batch.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/history/{id}/generate/progress")
    public ResponseEntity<ApiResponse<OrderImportService.GenProgressView>> generationProgress(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        OrderImportService.GenProgressView view = orderImportService.generationProgress(id);
        return ResponseEntity.ok(ApiResponse.<OrderImportService.GenProgressView>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .data(view)
                .build());
    }

    @Operation(summary = "Correct one row of a saved import (Data History inline edit)",
            description = "Sprint 51 — replaces the row with the operator's edit, re-validates the " +
                    "whole batch, re-stamps each ungenerated row SAVED / NEEDS_FIX, and recomputes " +
                    "the batch counts + status. Rows that already have a label are immutable.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @org.springframework.web.bind.annotation.PutMapping("/history/{id}/rows/{rowNumber}")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.ImportBatchDTO>> updateRow(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.PathVariable int rowNumber,
            @RequestBody OrderImportRowDTO edited,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        com.multiship.backend.dto.ImportBatchDTO dto = orderImportService.updateBatchRow(id, rowNumber, edited, username);
        if (dto == null) {
            return ResponseEntity.status(404).body(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                    .status("ERROR").code(404).timestamp(java.time.LocalDateTime.now())
                    .message("Import not found.")
                    .build());
        }
        long held = dto.getRows() == null ? 0 : dto.getRows().stream()
                .filter(r -> "NEEDS_FIX".equalsIgnoreCase(r.getGeneratedStatus())).count();
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message(held == 0 ? "Row " + rowNumber + " saved · all rows ready"
                        : "Row " + rowNumber + " saved · " + held + " row(s) still need fixing")
                .data(dto)
                .build());
    }

    @Operation(summary = "Re-validate rows (dry-run, JSON in / JSON out)",
            description = "Sprint 48 — same pipeline as /preview (required fields, name→code " +
                    "resolution, international-item rule) but takes JSON rows directly so the " +
                    "operator can re-check edits without re-uploading a file.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<OrderImportPreviewDTO>> validate(
            @RequestBody List<OrderImportRowDTO> rows) {
        ApiResponse<OrderImportPreviewDTO> response = orderImportService.validate(rows);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Address-check each row against its picked carrier",
            description = "Sprint 48 — for every row that carries a carrierCode + recipient " +
                    "address block, calls the picked carrier's own address-validation API via " +
                    "AddressValidationService. Invalid addresses append a NON-FATAL warning; " +
                    "the row's errors list stays untouched so operators still commit at will.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/validate-addresses")
    public ResponseEntity<ApiResponse<OrderImportPreviewDTO>> validateAddresses(
            @RequestBody List<OrderImportRowDTO> rows) {
        ApiResponse<OrderImportPreviewDTO> response = orderImportService.validateAddresses(rows);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Download the CSV template",
            description = "Public — the template is static schema (headers + one dummy row) " +
                    "and downloads via a browser <a href> that carries no Authorization header.")
    @GetMapping(value = "/template.csv", produces = com.multiship.backend.common.CsvMediaType.CSV_UTF8)
    public ResponseEntity<byte[]> template() {
        byte[] csv = orderImportService.csvTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"order-import-template.csv\"")
                // Sprint 51 AC-L5 — canonical UTF-8 CSV media type.
                .contentType(MediaType.parseMediaType(com.multiship.backend.common.CsvMediaType.CSV_UTF8))
                .body(csv);
    }

    @Operation(summary = "Download the XLSX template (data-validation dropdowns + samples)",
            description = "Sprint 48 — richer template with dropdowns, sample multi-row order, " +
                    "and an instructions sheet. When `accountId` is supplied the sample rows " +
                    "prefill accountNumber + carrierCode and the serviceType / packageType " +
                    "dropdowns narrow to that carrier's options only. Requires authentication " +
                    "because accountId resolves against private account data. " +
                    "For generic (unscoped) downloads, if an admin has dropped a macro-enabled " +
                    "workbook at resources/templates/order-import-template.xlsm, that file is " +
                    "served in place of the POI-generated .xlsx — same download URL, richer " +
                    "in-workbook UX (Save-as-CSV + Validate-All buttons). Account-scoped " +
                    "downloads always use the dynamic .xlsx generator.")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/template.xlsx",
            produces = {
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel.sheet.macroEnabled.12"
            })
    public ResponseEntity<byte[]> templateXlsx(
            @io.swagger.v3.oas.annotations.Parameter(description = "Optional carrier account id to scope the template to.")
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long accountId) throws java.io.IOException {
        // Prefer the static .xlsm resource ONLY for generic downloads —
        // it can't reflect per-account scoping (dropdown restrictions +
        // prefill), so scoped downloads always go through the dynamic
        // POI generator regardless.
        //
        // Two accepted paths: `order-import-template.xlsm` (canonical) or
        // `order-import-template-generic.xlsm` (the name suggested by the
        // dynamic download's filename convention — some admins upload
        // under the `-generic` name to mirror what they downloaded).
        // First match wins.
        if (accountId == null) {
            String[] candidates = {
                    "templates/order-import-template.xlsm",
                    "templates/order-import-template-generic.xlsm",
            };
            for (String path : candidates) {
                org.springframework.core.io.Resource xlsm =
                        new org.springframework.core.io.ClassPathResource(path);
                if (xlsm.exists()) {
                    byte[] bytes = xlsm.getInputStream().readAllBytes();
                    // Serve under the canonical filename regardless of which
                    // resource path matched, so downstream tooling / URLs
                    // don't diverge on the `-generic` suffix.
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"order-import-template.xlsm\"")
                            .contentType(MediaType.parseMediaType(
                                    "application/vnd.ms-excel.sheet.macroEnabled.12"))
                            .body(bytes);
                }
            }
        }
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
