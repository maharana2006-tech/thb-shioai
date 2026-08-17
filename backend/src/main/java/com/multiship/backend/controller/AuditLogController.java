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
        Page<AuditLog> result = repo.search(safeActor, safeEntity, safeAction, safeKey, sinceTs, untilTs,
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
    private static LocalDateTime parseOrDefault(String s, LocalDateTime fallback, String paramName) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDateTime.parse(s.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "'" + paramName + "' must be an ISO-8601 local timestamp (yyyy-MM-ddTHH:mm:ss)");
        }
    }

    /**
     * Audit A3 — parse a "property,direction" spec from the query string
     * into a Spring {@link Sort}. Whitelists the properties the repo's
     * buildOrderBy switch also whitelists so an unknown key falls back
     * to createdAt (rather than reaching the persistence layer with an
     * unmapped column).
     */
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
