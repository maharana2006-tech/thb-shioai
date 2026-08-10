package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.service.BulkLabelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Optional;

/**
 * Sprint 37 — bulk label generation. Three endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/bulk-labels} — submit a batch of order
 *       numbers, get a job ID back immediately (returns before the
 *       carrier calls start).</li>
 *   <li>{@code GET /api/v1/bulk-labels/{jobId}} — poll for status;
 *       includes {@code successfulCount} / {@code failedCount} while
 *       the job runs.</li>
 *   <li>{@code GET /api/v1/bulk-labels/{jobId}/download} — stream the
 *       ZIP of successful labels once the job is COMPLETED.</li>
 * </ul>
 */
@Tag(name = "Bulk labels", description = "Batch label generation (Sprint 37)")
@RestController
@RequestMapping("/api/v1/bulk-labels")
@RequiredArgsConstructor
public class BulkLabelController {

    private final BulkLabelService bulkLabelService;

    @Operation(summary = "Submit a bulk-label job",
            description = "Kicks off an async job that generates labels for every order in the request. " +
                    "Returns immediately with the job ID; poll status via GET /{jobId} and download the " +
                    "resulting ZIP via GET /{jobId}/download once status=COMPLETED.")
    // Sprint 50 Tier 0.5 PR E - controller role gate stays broad; the
    // service verifies EVERY orderNumber in the request belongs to the
    // caller's tenant via TenantScopeEnforcer.
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<BulkLabelJobDTO>> submit(
            @Valid @RequestBody BulkLabelRequestDTO request,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        ApiResponse<BulkLabelJobDTO> response = bulkLabelService.submit(request, username);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Poll a bulk-label job's status")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<BulkLabelJobDTO>> status(@PathVariable Long jobId) {
        ApiResponse<BulkLabelJobDTO> response = bulkLabelService.status(jobId);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Download the ZIP of successful labels",
            description = "Only available once the job's status is COMPLETED AND at least one label " +
                    "was generated successfully. Returns 404 otherwise so the caller can distinguish " +
                    "not-ready from job-not-found.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{jobId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long jobId) {
        Optional<BulkLabelJob> job = bulkLabelService.findRaw(jobId);
        if (job.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        BulkLabelJob j = job.get();
        if (j.getResultZipBase64() == null || j.getResultZipBase64().isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] zip = Base64.getDecoder().decode(j.getResultZipBase64());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bulk-labels-" + jobId + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }
}
