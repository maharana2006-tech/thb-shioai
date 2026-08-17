package com.multiship.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ScheduledReportDTO;
import com.multiship.backend.model.GeneratedReport;
import com.multiship.backend.model.ScheduledReport;
import com.multiship.backend.repository.GeneratedReportRepository;
import com.multiship.backend.repository.ScheduledReportRepository;
import com.multiship.backend.service.ScheduledReportRunner;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.external.WebhookUrlValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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
    /** Sprint 50 Tier 0.5 PR G — tenant clamp on schedule / generated
     *  rows so a scoped USER can't read, write, or trigger a foreign
     *  tenant's schedule. Operators pass through unchanged. */
    private final TenantScopeEnforcer tenantScope;
    /** Audit B7 — SSRF guard on WEBHOOK delivery URLs. Same shared
     *  validator that guards the /webhook-subscriptions save path. */
    private final WebhookUrlValidator webhookUrlValidator;
    /** Audit B3 — parse filtersJson at save time so malformed filters
     *  reject at 400 instead of blowing up the runner at tick time. */
    private final ObjectMapper objectMapper;

    // ===== CRUD =====

    @Operation(summary = "List schedules")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduledReportDTO>>> list(
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        // Sprint 50 Tier 0.5 PR G — clamp tenantId so a scoped USER
        // omitting the param sees only their own schedules; foreign
        // spoofed value → 403. Operators pass through unchanged.
        String scoped = tenantScope.clampClientCode(tenantId);
        List<ScheduledReport> rows = (scoped == null || scoped.isBlank())
                ? scheduleRepo.findAllByOrderByNameAsc()
                : scheduleRepo.findByTenantIdOrderByNameAsc(scoped);
        return ok(rows.stream().map(ScheduledReportDTO::from).toList(), "Schedules loaded");
    }

    @Operation(summary = "Upsert a schedule")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<ScheduledReportDTO>> save(@RequestBody ScheduledReportDTO body) {
        // Sprint 50 Tier 0.5 PR G — clamp body.tenantId so a scoped USER
        // can't create/edit a schedule aimed at a foreign tenant.
        body.setTenantId(tenantScope.clampClientCode(body.getTenantId()));

        // Audit B9 — enforce delivery config here (FE validates too, but was
        // bypassable via direct API call: could persist WEBHOOK type with no
        // deliveryWebhookUrl and the runner would silently fail on tick).
        if ("WEBHOOK".equalsIgnoreCase(String.valueOf(body.getDeliveryType()))
                && (body.getDeliveryWebhookUrl() == null || body.getDeliveryWebhookUrl().isBlank())) {
            return badRequest("WEBHOOK delivery requires deliveryWebhookUrl.");
        }
        if ("EMAIL".equalsIgnoreCase(String.valueOf(body.getDeliveryType()))
                && (body.getDeliveryEmail() == null || body.getDeliveryEmail().isBlank())) {
            return badRequest("EMAIL delivery requires deliveryEmail.");
        }
        // Audit B7 — SSRF guard on the outbound webhook URL. Same shared
        // validator that rejects webhook subscriptions to private / metadata
        // hosts. Fails-closed with a 400 + the actual reason.
        if ("WEBHOOK".equalsIgnoreCase(String.valueOf(body.getDeliveryType()))) {
            try {
                webhookUrlValidator.validate(body.getDeliveryWebhookUrl());
            } catch (WebhookUrlValidator.WebhookUrlRejectedException ex) {
                return badRequest(ex.getMessage());
            }
        }
        // Audit B3 — validate filtersJson at save time. Pre-fix, a typo made
        // the runner blow up at the next tick with no user-visible signal
        // (schedule looked fine in the list, no generated row appeared).
        if (body.getFiltersJson() != null && !body.getFiltersJson().isBlank()) {
            try {
                objectMapper.readTree(body.getFiltersJson());
            } catch (Exception parseErr) {
                return badRequest("filtersJson is not valid JSON: " + parseErr.getMessage());
            }
        }

        ScheduledReport s = body.toEntity();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (s.getId() == null) {
            s.setCreatedAt(now);
            s.setNextRunAt(now);   // fire on next tick
            // G6 — stamp creator + role from the SecurityContext so the
            // runner can later enforce the caller's scope on the CSV output.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                s.setCreatedBy(auth.getName());
                s.setCreatedByRole(primaryRole(auth));
            }
        } else {
            // G6 — updates never overwrite creator / role. Preserve from the
            // existing row so audit history survives a save from anyone else.
            scheduleRepo.findById(s.getId()).ifPresent(existing -> {
                s.setCreatedBy(existing.getCreatedBy());
                s.setCreatedByRole(existing.getCreatedByRole());
                s.setCreatedAt(existing.getCreatedAt());
            });
        }
        s.setUpdatedAt(now);
        ScheduledReport saved = scheduleRepo.save(s);
        HttpStatus code = body.getId() == null ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(code).body(ApiResponse.<ScheduledReportDTO>builder()
                .status("success").code(code.value()).message("Schedule saved")
                .data(ScheduledReportDTO.from(saved)).build());
    }

    /** Extract the caller's primary role. Prefers ROLE_TENANT (most restrictive)
     *  then ROLE_ADMIN, then ROLE_USER, matching {@code OrderAccessEvaluator}'s
     *  precedence. Returns null when no known role is present. */
    private static String primaryRole(Authentication auth) {
        java.util.Set<String> roles = new java.util.HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) roles.add(ga.getAuthority());
        if (roles.contains("ROLE_TENANT")) return "ROLE_TENANT";
        if (roles.contains("ROLE_ADMIN")) return "ROLE_ADMIN";
        if (roles.contains("ROLE_USER")) return "ROLE_USER";
        return null;
    }

    @Operation(summary = "Delete a schedule")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        // Audit B1 — pre-fix, deleteById(id) fired unconditionally: an ADMIN
        // hitting DELETE /scheduled-reports/{someone-else's-id} silently
        // deleted a foreign tenant's schedule (200 "Schedule deleted"). Now
        // 404 for missing, tenantScope enforcement for cross-tenant.
        ScheduledReport existing = scheduleRepo.findById(id).orElse(null);
        if (existing == null) return notFound("No such schedule");
        // For scoped USERs the requireTenantMatch below throws 403; for
        // platform-wide ADMINs it's a pass-through so any tenant's schedule
        // can still be deleted from the admin console.
        tenantScope.requireTenantMatch(existing.getTenantId());
        scheduleRepo.delete(existing);
        return ok(null, "Schedule deleted");
    }

    // ===== Run now =====

    @Operation(summary = "Run a schedule immediately")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{id}/run-now")
    public ResponseEntity<ApiResponse<Long>> runNow(@PathVariable Long id) {
        ScheduledReport s = scheduleRepo.findById(id).orElse(null);
        if (s == null) return notFound("No such schedule");
        // Sprint 50 Tier 0.5 PR G — belt guard on loaded schedule so a
        // scoped USER can't trigger a foreign tenant's schedule by id.
        tenantScope.requireTenantMatch(s.getTenantId());
        GeneratedReport gr = runner.runOne(s, LocalDateTime.now(ZoneOffset.UTC));
        return ok(gr.getId(), "Run completed (generated #" + gr.getId() + ")");
    }

    // ===== Generated (dashboard) =====

    @Operation(summary = "List recent generated reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/generated")
    public ResponseEntity<ApiResponse<List<GeneratedReport>>> generated(
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        // Sprint 50 Tier 0.5 PR G — clamp tenantId so a scoped USER
        // sees only their own generated reports.
        String scoped = tenantScope.clampClientCode(tenantId);
        List<GeneratedReport> rows = (scoped == null || scoped.isBlank())
                ? generatedRepo.findTop50ByOrderByGeneratedAtDesc()
                : generatedRepo.findByTenantIdOrderByGeneratedAtDesc(scoped);
        // Strip csvBytes on list — download hits the dedicated endpoint below.
        rows.forEach(r -> r.setCsvBytes(null));
        return ok(rows, "Recent generated");
    }

    @Operation(summary = "Download a generated report's CSV")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/generated/{id}/download", produces = com.multiship.backend.common.CsvMediaType.CSV_UTF8)
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        GeneratedReport gr = generatedRepo.findById(id).orElse(null);
        if (gr == null || gr.getCsvBytes() == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Not found");
            return;
        }
        // Sprint 50 Tier 0.5 PR G — belt guard on loaded report so a
        // scoped USER can't download a foreign tenant's CSV by id.
        tenantScope.requireTenantMatch(gr.getTenantId());
        // Sprint 51 AC-L5 — canonical UTF-8 CSV content-type.
        response.setContentType(com.multiship.backend.common.CsvMediaType.CSV_UTF8);
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

    /** Audit B7/B9/B3 — 400 with the actual reason so operators see the
     *  hint (SSRF-blocked URL / missing recipient / bad JSON) rather than
     *  a generic error. */
    private static <T> ResponseEntity<ApiResponse<T>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }
}
