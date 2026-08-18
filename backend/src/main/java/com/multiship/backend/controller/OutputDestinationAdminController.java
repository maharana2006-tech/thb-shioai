package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.OutputDestinationDTO;
import com.multiship.backend.dto.OutputDestinationUpsertRequest;
import com.multiship.backend.service.output.DispatchResult;
import com.multiship.backend.service.output.OutputDestinationAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 52 — admin CRUD for
 * {@link com.multiship.backend.model.ClientOutputDestination}. Powers
 * the {@code /settings/output-destinations} page. ADMIN-only.
 *
 * <p>All responses use {@link ApiResponse}. SFTP password / key material
 * is <b>write-only</b>: POST + PUT accept it under {@code sftpPasswordPlain}
 * / {@code sftpPrivateKeyPlain}, GET responses NEVER echo it — the DTO
 * exposes only a {@code ***set***} marker on the sanitised config.
 */
@Tag(name = "Admin output destinations",
        description = "Sprint 52 — per-client output routing (LOCAL_FS / SFTP / PRINTER) for generated labels + commercial invoices.")
@RestController
@RequestMapping("/api/v1/admin/output-destinations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class OutputDestinationAdminController {

    private final OutputDestinationAdminService adminService;

    @Operation(summary = "List output destinations",
            description = "Filterable by clientCode. Config JSON is returned sanitised — SFTP secret pointers appear as `***set***`.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<OutputDestinationDTO>>> list(
            @RequestParam(required = false) String clientCode) {
        List<OutputDestinationDTO> data = adminService.list(clientCode);
        return ResponseEntity.ok(ApiResponse.<List<OutputDestinationDTO>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(data.size() + " destination(s).")
                .data(data).build());
    }

    @Operation(summary = "Get a single output destination")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OutputDestinationDTO>> get(@PathVariable Long id) {
        return adminService.get(id)
                .map(dto -> ResponseEntity.ok(ApiResponse.<OutputDestinationDTO>builder()
                        .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                        .message("Destination " + id + ".").data(dto).build()))
                .orElseGet(() -> ResponseEntity.status(404).body(
                        err(ErrorCode.OUTPUT_DESTINATION_NOT_FOUND, 404,
                                "Destination " + id + " not found.")));
    }

    @Operation(summary = "Create an output destination",
            description = "SFTP password / private-key material must be submitted as `sftpPasswordPlain` / `sftpPrivateKeyPlain`; the server encrypts + stores it and swaps in a pointer id on the saved config. "
                    + "Audit R2 #344 — SSRF-guarded: SFTP/PRINTER hosts that resolve to private / loopback / link-local ranges or a cloud-metadata endpoint are rejected at 400.")
    @PostMapping
    public ResponseEntity<ApiResponse<OutputDestinationDTO>> create(
            @Valid @RequestBody OutputDestinationUpsertRequest req,
            @AuthenticationPrincipal UserDetails actor) {
        try {
            OutputDestinationDTO dto = adminService.create(req, actor != null ? actor.getUsername() : "system");
            return ResponseEntity.status(201).body(ApiResponse.<OutputDestinationDTO>builder()
                    .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                    .message("Destination created.").data(dto).build());
        } catch (IllegalArgumentException ex) {
            // Audit R2 #344 — SSRF guard + processConfig parse errors both
            // surface as IllegalArgumentException. 400 with the actual reason
            // so ops sees the block hint immediately.
            return ResponseEntity.badRequest().body(
                    err(ErrorCode.VALIDATION_ERROR, 400, ex.getMessage()));
        }
    }

    @Operation(summary = "Update an output destination",
            description = "Audit R2 #344 — SSRF-guarded on the same rules as create.")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<OutputDestinationDTO>> update(
            @PathVariable Long id,
            @Valid @RequestBody OutputDestinationUpsertRequest req,
            @AuthenticationPrincipal UserDetails actor) {
        try {
            return adminService.update(id, req, actor != null ? actor.getUsername() : "system")
                    .map(dto -> ResponseEntity.ok(ApiResponse.<OutputDestinationDTO>builder()
                            .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                            .message("Destination updated.").data(dto).build()))
                    .orElseGet(() -> ResponseEntity.status(404).body(
                            err(ErrorCode.OUTPUT_DESTINATION_NOT_FOUND, 404,
                                    "Destination " + id + " not found.")));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(
                    err(ErrorCode.VALIDATION_ERROR, 400, ex.getMessage()));
        }
    }

    @Operation(summary = "Delete an output destination",
            description = "Removes the row and best-effort deletes any attached SFTP secret rows.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (adminService.delete(id)) {
            return ResponseEntity.ok(ApiResponse.<Void>builder()
                    .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                    .message("Destination " + id + " deleted.").build());
        }
        return ResponseEntity.status(404).body(ApiResponse.<Void>builder()
                .status("ERROR").code(404).timestamp(LocalDateTime.now())
                .errorCode(ErrorCode.OUTPUT_DESTINATION_NOT_FOUND.name())
                .message("Destination " + id + " not found.").build());
    }

    @Operation(summary = "Test-dispatch a synthetic payload to the destination",
            description = "Sends a doc-type-appropriate synthetic payload (ZPL for Zebra RAW / file drops, "
                    + "1-page PDF for IPP + commercial-invoice destinations) so the operator can verify "
                    + "credentials / connectivity without generating a real shipment. Every payload embeds "
                    + "the current UTC timestamp for visual confirmation. 502 with `OUTPUT_DELIVERY_FAILED` on driver failure.")
    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<DispatchResult>> test(@PathVariable Long id) {
        DispatchResult result;
        try {
            result = adminService.test(id);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(404).body(errData(ErrorCode.OUTPUT_DESTINATION_NOT_FOUND, 404,
                    notFound.getMessage()));
        }
        if (result.getFailureCount() > 0) {
            String detail = result.getItems().isEmpty() ? "unknown failure"
                    : result.getItems().get(0).getFailureMessage();
            return ResponseEntity.status(502).body(ApiResponse.<DispatchResult>builder()
                    .status("ERROR").code(502).timestamp(LocalDateTime.now())
                    .errorCode(ErrorCode.OUTPUT_DELIVERY_FAILED.name())
                    .message("Test dispatch failed: " + detail)
                    .data(result).build());
        }
        return ResponseEntity.ok(ApiResponse.<DispatchResult>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("Test dispatch succeeded.").data(result).build());
    }

    private static ApiResponse<OutputDestinationDTO> err(ErrorCode code, int http, String msg) {
        return ApiResponse.<OutputDestinationDTO>builder()
                .status("ERROR").code(http).timestamp(LocalDateTime.now())
                .errorCode(code.name()).message(msg).build();
    }

    private static ApiResponse<DispatchResult> errData(ErrorCode code, int http, String msg) {
        return ApiResponse.<DispatchResult>builder()
                .status("ERROR").code(http).timestamp(LocalDateTime.now())
                .errorCode(code.name()).message(msg).build();
    }
}
