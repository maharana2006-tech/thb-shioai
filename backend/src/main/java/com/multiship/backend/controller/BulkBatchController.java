package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.service.BulkBatchQueryService;
import com.multiship.backend.service.BulkBatchQueryService.Query;
import com.multiship.backend.service.BulkBatchQueryService.Summary;
import com.multiship.backend.service.BulkBatchQueryService.View;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Locale;

/** Bulk Mailer's batch lists — filtered, sorted and paged by the server. */
@RestController
@RequestMapping("/api/v1/bulk")
@RequiredArgsConstructor
@Tag(name = "Bulk Mailer", description = "Import and API batches, paged and filtered")
public class BulkBatchController {

    private final BulkBatchQueryService queryService;
    private final com.multiship.backend.repository.ImportBatchRepository importBatchRepository;

    @Operation(summary = "One page of batches",
            description = "view = FILE (file imports) · API (WMS / external API) · TRASH (deleted, any source). "
                    + "Search matches the file name, the creator, #id and the label batch number.")
    @GetMapping("/batches")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<ApiResponse<Page<ImportBatchDTO>>> batches(
            @RequestParam(defaultValue = "FILE") String view,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) String labelBatch,
            @RequestParam(required = false) Integer minSaved,
            @RequestParam(defaultValue = "created") String sort,
            @RequestParam(defaultValue = "DESC") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<ImportBatchDTO> result = queryService.list(new Query(parseView(view), status, q, from, to, createdBy,
                labelBatch, minSaved, sort, "ASC".equalsIgnoreCase(dir)), page, size);
        return ResponseEntity.ok(ApiResponse.<Page<ImportBatchDTO>>builder()
                .status("success").code(200)
                .message(result.getTotalElements() + " batch(es).")
                .data(result).build());
    }

    @Operation(summary = "One batch's header, as the list shows it",
            description = "Facts, label counts, live orders and last print — never its rows (see "
                    + "GET /orders/import/history/{slug}/rows). Any view, Trash included. By the batch's "
                    + "opaque slug; an unknown slug is a 404 like a batch the caller can't see.")
    @GetMapping("/batches/{slug}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<ApiResponse<ImportBatchDTO>> batch(@PathVariable String slug) {
        return (slug == null || slug.isBlank() ? java.util.Optional.<com.multiship.backend.model.ImportBatch>empty()
                        : importBatchRepository.findBySlug(slug))
                .flatMap(b -> queryService.one(b.getId()))
                .map(d -> ResponseEntity.ok(ApiResponse.<ImportBatchDTO>builder()
                        .status("success").code(200).message("ok").data(d).build()))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<ImportBatchDTO>builder()
                        .status("ERROR").code(404).message("Import not found.").build()));
    }

    @Operation(summary = "Counts for the cards and status chips of one view",
            description = "Over the whole view, not the current filters. Also lists who created batches in it.")
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<ApiResponse<Summary>> summary(@RequestParam(defaultValue = "FILE") String view) {
        return ResponseEntity.ok(ApiResponse.<Summary>builder()
                .status("success").code(200).message("ok")
                .data(queryService.summary(parseView(view))).build());
    }

    private static View parseView(String view) {
        try {
            return View.valueOf(view.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return View.FILE;
        }
    }
}
