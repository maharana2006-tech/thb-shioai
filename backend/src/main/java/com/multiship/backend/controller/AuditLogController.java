package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.AuditLogDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.AuditLog;
import com.multiship.backend.repository.AuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.multiship.backend.util.CsvUtil;

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
            description = "Paginated list of recorded actions. Empty filter fields skip. "
                    + "Rows are scope-filtered at the repository layer (Sprint 51 follow-up "
                    + "BS-M3 full fix): platform operators see every row including system "
                    + "events (client_code IS NULL); a tenant-scoped USER sees strictly "
                    + "their own tenant's rows.")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
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
            @Parameter(description = "Logs-page tab: ACTIVITY | SHIPMENT | ERROR | SYSTEM. Empty = all. Legacy rows count as ACTIVITY.")
            @RequestParam(required = false) String category,
            @Parameter(description = "Exact order number — 'everything about order N'")
            @RequestParam(required = false) Integer orderNo,
            @Parameter(description = "ISO-8601 lower bound on createdAt")
            @RequestParam(required = false) String since,
            @Parameter(description = "ISO-8601 upper bound on createdAt")
            @RequestParam(required = false) String until,
            @Parameter(description = "Sort key + direction: createdAt|actor|action|entityType|entityKey, ASC|DESC. Defaults to createdAt,DESC.")
            @RequestParam(defaultValue = "createdAt,DESC") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        String safeActor = actor == null ? "" : actor.trim();
        String safeEntity = entityType == null ? "" : entityType.trim().toUpperCase();
        String safeAction = action == null ? "" : action.trim().toUpperCase();
        String safeKey = entityKey == null ? "" : entityKey.trim();
        // Audit B3 — was `parseOrDefault` which silently fell back to
        // 1970/9999 on malformed input, so the caller thought their filter
        // was honoured. Now returns 400 with the actual reason.
        LocalDateTime sinceTs;
        LocalDateTime untilTs;
        try {
            sinceTs = parseOrDefault(since, LocalDateTime.of(1970, 1, 1, 0, 0), "since");
            untilTs = parseOrDefault(until, LocalDateTime.of(9999, 12, 31, 23, 59), "until");
            if (untilTs.isBefore(sinceTs)) {
                throw new com.multiship.backend.exception.FieldValidationException("until",
                        "'until' must be on or after 'since'.", HttpStatus.BAD_REQUEST);
            }
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.<PageResponseDTO<AuditLogDTO>>builder()
                    .status("error").code(HttpStatus.BAD_REQUEST.value())
                    .message(ex.getMessage())
                    .errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);

        // Audit A3 — parse the client's sort spec (previously ignored;
        // FE captured column-header clicks into state but never sent
        // them to the API, so all sorts silently defaulted to createdAt,DESC).
        Sort sortSpec = parseSort(sort);
        String safeCategory = category == null ? "" : category.trim().toUpperCase();
        Page<AuditLog> result = repo.search(safeActor, safeEntity, safeAction, safeKey,
                safeCategory, orderNo, sinceTs, untilTs,
                PageRequest.of(safePage, safeSize, sortSpec));

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

    /**
     * Audit B3 — parses a caller-supplied ISO-8601 timestamp, returning
     * {@code fallback} only when the caller omitted the parameter. Any
     * NON-empty value that fails to parse now throws — the controller
     * translates that into a 400 with the actual reason instead of
     * silently coercing to 1970/9999 (which made the filter box lie).
     */
    /**
     * BUG-156 — the page sends the operator's local time with its offset
     * (ISO-8601, e.g. {@code 2026-10-25T16:00:00.000Z} or {@code +05:30});
     * rows are stored in UTC, so compare in UTC. A zone-less value is read as
     * UTC for callers that still send one.
     */
    static LocalDateTime parseOrDefault(String s, LocalDateTime fallback, String paramName) {
        if (s == null || s.isBlank()) return fallback;
        String v = s.trim();
        try {
            return java.time.OffsetDateTime.parse(v).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception offsetless) {
            try {
                return LocalDateTime.parse(v);
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "'" + paramName + "' must be an ISO-8601 timestamp (2026-10-25T16:00:00Z or 2026-10-25T16:00)");
            }
        }
    }

    /**
     * Audit A3 — parse a "property,direction" spec from the query string
     * into a Spring {@link Sort}. Whitelists the properties the repo's
     * buildOrderBy switch also whitelists so an unknown key falls back
     * to createdAt (rather than reaching the persistence layer with an
     * unmapped column).
     */
    /** BUG-158 — shared with the Logs page export (AuditLogPage.tsx). */
    static final String EXPORT_HEADER =
            "When (UTC),Actor,Action,Order,Type,Entity,Notes,ID,Entity ID,Client,Category,Severity,Changes";

    /** Audit B6 (#357) — row cap on the CSV export so a mis-filtered dump
     *  doesn't OOM the service. 100k rows covers the typical compliance
     *  ask ("last quarter") with room to spare; past that, the auditor
     *  gets the first 100k and a header line hinting at the cap. */
    static final int EXPORT_ROW_CAP = 100_000;

    private static final DateTimeFormatter FILENAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Operation(summary = "Export audit rows as CSV",
            description = "Audit B6 (#357) — compliance-oriented range export. Same filters as "
                    + "the list endpoint; capped at " + EXPORT_ROW_CAP + " rows. ADMIN-only "
                    + "(list allows USER, export is a bigger blast radius). Tenant scoping on the "
                    + "repository fragment still clamps scoped operators to their own rows.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/export", produces = "text/csv")
    public void export(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityKey,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer orderNo,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            HttpServletResponse response) throws IOException {
        String safeActor = actor == null ? "" : actor.trim();
        String safeEntity = entityType == null ? "" : entityType.trim().toUpperCase();
        String safeAction = action == null ? "" : action.trim().toUpperCase();
        String safeKey = entityKey == null ? "" : entityKey.trim();
        String safeCategory = category == null ? "" : category.trim().toUpperCase();

        LocalDateTime sinceTs;
        LocalDateTime untilTs;
        try {
            sinceTs = parseOrDefault(since, LocalDateTime.of(1970, 1, 1, 0, 0), "since");
            untilTs = parseOrDefault(until, LocalDateTime.of(9999, 12, 31, 23, 59), "until");
        } catch (IllegalArgumentException ex) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getWriter().write(ex.getMessage());
            return;
        }

        boolean hasSince = since != null && !since.isBlank();
        boolean hasUntil = until != null && !until.isBlank();
        String filename = "audit-"
                + (hasSince ? FILENAME_FMT.format(sinceTs.toLocalDate()) : "all")
                + (hasUntil ? "-to-" + FILENAME_FMT.format(untilTs.toLocalDate()) : "")
                + ".csv";
        response.setContentType("text/csv; charset=utf-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        Page<AuditLog> result = repo.search(safeActor, safeEntity, safeAction, safeKey,
                safeCategory, orderNo, sinceTs, untilTs,
                PageRequest.of(0, EXPORT_ROW_CAP, Sort.by(Sort.Direction.DESC, "createdAt")));

        PrintWriter out = response.getWriter();
        // BUG-158 — same columns and order as the Logs page's own Export, and
        // the stamp says which zone it is in.
        out.println(EXPORT_HEADER);
        for (AuditLog r : result.getContent()) {
            out.print(csv(r.getCreatedAt() == null ? "" : r.getCreatedAt().atOffset(java.time.ZoneOffset.UTC).toString())); out.print(',');
            out.print(csv(r.getActor())); out.print(',');
            out.print(csv(r.getAction())); out.print(',');
            out.print(r.getOrderNo() == null ? "" : r.getOrderNo()); out.print(',');
            out.print(csv(r.getEntityType())); out.print(',');
            out.print(csv(r.getEntityKey())); out.print(',');
            out.print(csv(r.getNotes())); out.print(',');
            out.print(r.getId()); out.print(',');
            out.print(csv(r.getEntityId())); out.print(',');
            out.print(csv(r.getClientCode())); out.print(',');
            // Legacy rows have no category / severity; the list normalises them the same way.
            out.print(csv(r.getCategory() == null ? "ACTIVITY" : r.getCategory())); out.print(',');
            out.print(csv(r.getSeverity() == null ? "INFO" : r.getSeverity())); out.print(',');
            out.println(csv(r.getChanges()));
        }
        if (result.getTotalElements() > EXPORT_ROW_CAP) {
            out.println();
            out.println("# NOTE: result truncated at " + EXPORT_ROW_CAP + " rows (of "
                    + result.getTotalElements() + "). Narrow the filter and re-export.");
        }
        out.flush();
    }

    /** RFC-4180 quoting + BUG-076 formula-injection guard, via the shared
     *  {@link CsvUtil#csvCell}. This /export emits tenant-authored notes /
     *  entityKey / changes across all tenants to an admin, so an un-neutralized
     *  leading =/+/-/@ cell would execute on the admin's spreadsheet. */
    static String csv(Object value) {
        return CsvUtil.csvCell(value == null ? "" : String.valueOf(value));
    }

    private static Sort parseSort(String raw) {
        if (raw == null || raw.isBlank()) return Sort.by(Sort.Direction.DESC, "createdAt");
        String[] parts = raw.split(",");
        String prop = switch (parts[0].trim()) {
            case "actor", "action", "entityType", "entityKey", "createdAt" -> parts[0].trim();
            default -> "createdAt";
        };
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, prop);
    }
}
