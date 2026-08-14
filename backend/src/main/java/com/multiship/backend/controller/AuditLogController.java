package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.AuditLogDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.AuditLog;
import com.multiship.backend.repository.AuditLogRepository;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only endpoint powering the settings > Audit Log page.
 * Writes happen implicitly via {@link com.multiship.backend.service.AuditService}
 * inside other controllers.
 */
@Tag(name = "Audit log", description = "Read-only trail of settings writes")
@RestController
@RequestMapping("/api/v1/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository repo;

    @Operation(summary = "List audit rows",
            description = "Paginated list of every recorded action. Empty filter fields skip. "
                    + "Restricted to ADMIN — the payload includes cross-tenant activity and "
                    + "there is no per-tenant filter yet (see Sprint 51 BS-M3: full fix is a "
                    + "future client_code column + scope filter).")
    // Sprint 51 BS-M3 (interim) — was hasAnyRole('ADMIN','USER'). USER-tenants
    // could enumerate other tenants' audit rows via the actor / entityKey
    // filters. Full fix (column + scope filter) is deferred to a separate
    // migration cycle; ADMIN-only closes the leak in the meantime.
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponseDTO<AuditLogDTO>>> list(
            @Parameter(description = "Case-insensitive substring on actor username")
            @RequestParam(required = false) String actor,
            @Parameter(description = "CLIENT | WAREHOUSE | CARRIER_ACCOUNT | ...")
            @RequestParam(required = false) String entityType,
            @Parameter(description = "CREATE | UPDATE | DELETE | TOGGLE_ACTIVE | CASCADE_DISABLE | CASCADE_ENABLE")
            @RequestParam(required = false) String action,
            @Parameter(description = "Case-insensitive substring on human-readable key (client code, warehouse code, etc.)")
            @RequestParam(required = false) String entityKey,
            @Parameter(description = "ISO-8601 lower bound on createdAt")
            @RequestParam(required = false) String since,
            @Parameter(description = "ISO-8601 upper bound on createdAt")
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        String safeActor = actor == null ? "" : actor.trim();
        String safeEntity = entityType == null ? "" : entityType.trim().toUpperCase();
        String safeAction = action == null ? "" : action.trim().toUpperCase();
        String safeKey = entityKey == null ? "" : entityKey.trim();
        // Substitute sentinel bounds when the caller doesn't specify
        // a date filter — Postgres can't infer the type of a null
        // LocalDateTime bound. Same fix pattern as LabelTemplateRepo.
        LocalDateTime sinceTs = parseOrDefault(since, LocalDateTime.of(1970, 1, 1, 0, 0));
        LocalDateTime untilTs = parseOrDefault(until, LocalDateTime.of(9999, 12, 31, 23, 59));
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);

        Page<AuditLog> result = repo.search(safeActor, safeEntity, safeAction, safeKey, sinceTs, untilTs,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<AuditLogDTO> content = result.getContent().stream().map(AuditLogDTO::from).toList();
        PageResponseDTO<AuditLogDTO> body = PageResponseDTO.of(
                content, result.getNumber(), result.getSize(), result.getTotalElements(),
                "createdAt", "DESC");

        return ResponseEntity.ok(ApiResponse.<PageResponseDTO<AuditLogDTO>>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message(body.getTotalElements() + " audit entrie(s)")
                .data(body)
                .build());
    }

    private static LocalDateTime parseOrDefault(String s, LocalDateTime fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDateTime.parse(s.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }
}
