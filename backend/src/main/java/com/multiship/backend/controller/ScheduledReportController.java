package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ScheduledReportDTO;
import com.multiship.backend.model.GeneratedReport;
import com.multiship.backend.model.ScheduledReport;
import com.multiship.backend.repository.GeneratedReportRepository;
import com.multiship.backend.repository.ScheduledReportRepository;
import com.multiship.backend.service.ScheduledReportRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Sprint 45 — scheduled report CRUD + "run now" + generated download.
 */
@Tag(name = "Scheduled reports",
        description = "Recurring CSV exports with dashboard / email / webhook delivery (Sprint 45)")
@RestController
@RequestMapping("/api/v1/scheduled-reports")
@RequiredArgsConstructor
public class ScheduledReportController {

    private final ScheduledReportRepository scheduleRepo;
    private final GeneratedReportRepository generatedRepo;
    private final ScheduledReportRunner runner;

    // ===== CRUD =====

    @Operation(summary = "List schedules")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduledReportDTO>>> list(
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        List<ScheduledReport> rows = (tenantId == null || tenantId.isBlank())
                ? scheduleRepo.findAllByOrderByNameAsc()
                : scheduleRepo.findByTenantIdOrderByNameAsc(tenantId);
        return ok(rows.stream().map(ScheduledReportDTO::from).toList(), "Schedules loaded");
    }

    @Operation(summary = "Upsert a schedule")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<ScheduledReportDTO>> save(@RequestBody ScheduledReportDTO body) {
        ScheduledReport s = body.toEntity();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (s.getId() == null) {
            s.setCreatedAt(now);
            s.setNextRunAt(now);   // fire on next tick
        }
        s.setUpdatedAt(now);
        ScheduledReport saved = scheduleRepo.save(s);
        HttpStatus code = body.getId() == null ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(code).body(ApiResponse.<ScheduledReportDTO>builder()
                .status("success").code(code.value()).message("Schedule saved")
                .data(ScheduledReportDTO.from(saved)).build());
    }

    @Operation(summary = "Delete a schedule")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        scheduleRepo.deleteById(id);
        return ok(null, "Schedule deleted");
    }

    // ===== Run now =====

    @Operation(summary = "Run a schedule immediately")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{id}/run-now")
    public ResponseEntity<ApiResponse<Long>> runNow(@PathVariable Long id) {
        ScheduledReport s = scheduleRepo.findById(id).orElse(null);
        if (s == null) return notFound("No such schedule");
        GeneratedReport gr = runner.runOne(s, LocalDateTime.now(ZoneOffset.UTC));
        return ok(gr.getId(), "Run completed (generated #" + gr.getId() + ")");
    }

    // ===== Generated (dashboard) =====

    @Operation(summary = "List recent generated reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/generated")
    public ResponseEntity<ApiResponse<List<GeneratedReport>>> generated(
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        List<GeneratedReport> rows = (tenantId == null || tenantId.isBlank())
                ? generatedRepo.findTop50ByOrderByGeneratedAtDesc()
                : generatedRepo.findByTenantIdOrderByGeneratedAtDesc(tenantId);
        // Strip csvBytes on list — download hits the dedicated endpoint below.
        rows.forEach(r -> r.setCsvBytes(null));
        return ok(rows, "Recent generated");
    }

    @Operation(summary = "Download a generated report's CSV")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/generated/{id}/download", produces = "text/csv")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        GeneratedReport gr = generatedRepo.findById(id).orElse(null);
        if (gr == null || gr.getCsvBytes() == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Not found");
            return;
        }
        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + gr.getFilename());
        response.getOutputStream().write(gr.getCsvBytes());
        response.getOutputStream().flush();
    }

    // ===== helpers =====

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value()).message(message).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.NOT_FOUND.value()).message(message).build());
    }
}
