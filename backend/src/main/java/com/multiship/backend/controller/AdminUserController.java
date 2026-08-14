package com.multiship.backend.controller;

import com.multiship.backend.dto.AdminUserAssignClientRequest;
import com.multiship.backend.dto.AdminUserAuditDTO;
import com.multiship.backend.dto.AdminUserDTO;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.service.AdminUserService;
import com.multiship.backend.service.AdminUserService.MutationOutcome;
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
 * Sprint 50 Tier 0.5 PR E — admin user management page backend. Powers
 * the {@code /settings/users} page: list, assign clientCode, deactivate
 * / reactivate, view audit trail. ADMIN-only.
 *
 * <p>The typical rollout workflow: an admin backfills {@code clientCode}
 * on every legacy USER, verifies the assignments on the audit tab, then
 * ops flips {@code access.scope-user-by-client=true} so the tenant-scope
 * gate becomes enforced. Getting the backfill wrong before the flag flip
 * is easy to fix here (reassign, or deactivate + reissue an invite).
 */
@Tag(name = "Admin users",
        description = "Sprint 50 PR E — assign clientCode to legacy USER rows, deactivate revoked accounts, and audit the changes.")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "List users",
            description = "Filterable by search (username/email/fullName), role, clientCode, active-only. "
                    + "Skinny projection — no password hashes or carrier secrets. "
                    + "Sprint 51 BP-L4: paginated (default 50 / max 100 per page); pre-BP-L4 the "
                    + "endpoint fetched every row and filtered in-JVM.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminUserDTO>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String clientCode,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        List<AdminUserDTO> data = adminUserService.list(search, role, clientCode, activeOnly, page, size);
        return ResponseEntity.ok(ApiResponse.<List<AdminUserDTO>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(data.size() + " user(s).")
                .data(data).build());
    }

    @Operation(summary = "Assign / clear a user's clientCode",
            description = "Passing null or blank clientCode clears the assignment (back to org-wide operator). "
                    + "404 ADMIN_TARGET_USER_NOT_FOUND if id unknown; 404 ADMIN_TARGET_CLIENT_NOT_FOUND if "
                    + "the target client doesn't exist.")
    @PatchMapping("/{id}/client")
    public ResponseEntity<ApiResponse<AdminUserDTO>> assignClient(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserAssignClientRequest req,
            @AuthenticationPrincipal UserDetails actor) {
        MutationOutcome outcome = adminUserService.assignClient(id, req.getClientCode(),
                req.getReason(), actor != null ? actor.getUsername() : "system");
        return respond(outcome, "Client assignment updated.");
    }

    @Operation(summary = "Deactivate a user",
            description = "Soft-delete: sets deactivated_at. Login is refused until reactivated. "
                    + "Idempotent — a re-deactivate returns 200 with the current state.")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<AdminUserDTO>> deactivate(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AdminUserAssignClientRequest body,
            @AuthenticationPrincipal UserDetails actor) {
        MutationOutcome outcome = adminUserService.deactivate(id,
                body == null ? null : body.getReason(),
                actor != null ? actor.getUsername() : "system");
        return respond(outcome, "User deactivated.");
    }

    @Operation(summary = "Reactivate a user")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<AdminUserDTO>> reactivate(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AdminUserAssignClientRequest body,
            @AuthenticationPrincipal UserDetails actor) {
        MutationOutcome outcome = adminUserService.reactivate(id,
                body == null ? null : body.getReason(),
                actor != null ? actor.getUsername() : "system");
        return respond(outcome, "User reactivated.");
    }

    @Operation(summary = "Recent admin actions (audit feed)")
    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<List<AdminUserAuditDTO>>> recentAudit(
            @RequestParam(defaultValue = "50") int limit) {
        List<AdminUserAuditDTO> data = adminUserService.recentAudit(limit);
        return ResponseEntity.ok(ApiResponse.<List<AdminUserAuditDTO>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(data.size() + " audit entry(-ies).")
                .data(data).build());
    }

    @Operation(summary = "Audit trail for one user")
    @GetMapping("/{id}/audit")
    public ResponseEntity<ApiResponse<List<AdminUserAuditDTO>>> userAudit(@PathVariable Long id) {
        List<AdminUserAuditDTO> data = adminUserService.auditForUser(id);
        return ResponseEntity.ok(ApiResponse.<List<AdminUserAuditDTO>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(data.size() + " audit entry(-ies) for user " + id + ".")
                .data(data).build());
    }

    private ResponseEntity<ApiResponse<AdminUserDTO>> respond(MutationOutcome outcome, String okMessage) {
        return switch (outcome.result()) {
            case OK -> ResponseEntity.ok(ApiResponse.<AdminUserDTO>builder()
                    .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                    .message(okMessage).data(outcome.user()).build());
            case ALREADY_IN_TARGET_STATE -> ResponseEntity.ok(ApiResponse.<AdminUserDTO>builder()
                    .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                    .message("No change — already in target state.").data(outcome.user()).build());
            case USER_NOT_FOUND -> ResponseEntity.status(404).body(err(ErrorCode.ADMIN_TARGET_USER_NOT_FOUND,
                    "User not found."));
            case CLIENT_NOT_FOUND -> ResponseEntity.status(404).body(err(ErrorCode.ADMIN_TARGET_CLIENT_NOT_FOUND,
                    "Target client not found."));
        };
    }

    private static ApiResponse<AdminUserDTO> err(ErrorCode code, String msg) {
        return ApiResponse.<AdminUserDTO>builder()
                .status("ERROR")
                .code(404)
                .timestamp(LocalDateTime.now())
                .errorCode(code.name())
                .message(msg)
                .build();
    }
}
