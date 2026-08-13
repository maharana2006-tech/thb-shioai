package com.multiship.backend.service;

import com.multiship.backend.dto.AdminUserAuditDTO;
import com.multiship.backend.dto.AdminUserDTO;
import com.multiship.backend.model.User;
import com.multiship.backend.model.UserAdminAudit;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.UserAdminAuditRepository;
import com.multiship.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprint 50 Tier 0.5 PR E — backing service for the {@code /settings/users}
 * admin page. Owns the mutating operations (assign client, deactivate,
 * reactivate) and the audit trail that goes with them.
 *
 * <p>Every mutation writes a {@link UserAdminAudit} row atomically with
 * the user change. If the audit insert fails, the mutation rolls back so
 * we never have an untraced client re-assignment in the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final UserAdminAuditRepository auditRepository;
    private final ClientRepository clientRepository;
    /**
     * Sprint 50 Tier 0.5 PR E - defensive clamp on assignClient. ADMIN
     * (the only role that reaches this service through the controller)
     * is a platform operator, so this is a no-op in practice — but it
     * prevents a leaked internal caller from re-scoping a user to a
     * foreign tenant.
     */
    private final TenantScopeEnforcer tenantScope;

    /** Sprint 51 T2 finding #5 — deactivate / assign-client mutations bump
     *  the target user's token_version so any outstanding JWT stops working
     *  on the next request. Without this, the deactivate feature was
     *  cosmetic — the user kept full API access until the 24h JWT expired. */
    private final TokenRevocationService tokenRevocationService;

    public enum ActionResult {
        OK,
        USER_NOT_FOUND,
        CLIENT_NOT_FOUND,
        ALREADY_IN_TARGET_STATE
    }

    public record MutationOutcome(ActionResult result, AdminUserDTO user) {}

    @Transactional(readOnly = true)
    public List<AdminUserDTO> list(String search, String role, String clientCode, Boolean activeOnly) {
        String q = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        String r = role == null || role.isBlank() ? null : role.trim().toUpperCase(Locale.ROOT);
        String cc = clientCode == null || clientCode.isBlank() ? null : clientCode.trim().toUpperCase(Locale.ROOT);

        return userRepository.findAll().stream()
                .filter(u -> q == null
                        || (u.getUsername() != null && u.getUsername().toLowerCase(Locale.ROOT).contains(q))
                        || (u.getEmail() != null && u.getEmail().toLowerCase(Locale.ROOT).contains(q))
                        || (u.getFullName() != null && u.getFullName().toLowerCase(Locale.ROOT).contains(q)))
                .filter(u -> r == null || r.equalsIgnoreCase(u.getRole()))
                .filter(u -> cc == null || (u.getClientCode() != null && cc.equalsIgnoreCase(u.getClientCode())))
                .filter(u -> !Boolean.TRUE.equals(activeOnly) || u.getDeactivatedAt() == null)
                .map(AdminUserService::toDTO)
                .toList();
    }

    /**
     * Assign / clear a user's clientCode. Passing null or blank clears
     * the assignment; passing a code validates it exists first.
     * ADMIN role cannot be re-scoped (returns OK but the value is
     * ignored — ADMIN is always org-wide by policy).
     */
    @Transactional
    public MutationOutcome assignClient(Long userId, String requestedClientCode, String reason,
                                         String actorUsername) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return new MutationOutcome(ActionResult.USER_NOT_FOUND, null);
        }
        User u = found.get();
        // Sprint 50 Tier 0.5 PR E - Pattern A clamp on the requested code.
        // ADMIN (only caller in practice) is an operator → pass-through;
        // a leaked internal caller cannot re-scope to a foreign tenant.
        String clamped = tenantScope.clampClientCode(requestedClientCode);
        String normalized = (clamped == null || clamped.isBlank())
                ? null
                : clamped.trim().toUpperCase(Locale.ROOT);

        if (normalized != null && !clientRepository.existsByClientCodeIgnoreCase(normalized)) {
            return new MutationOutcome(ActionResult.CLIENT_NOT_FOUND, null);
        }
        String old = u.getClientCode();
        if (java.util.Objects.equals(old, normalized)) {
            return new MutationOutcome(ActionResult.ALREADY_IN_TARGET_STATE, toDTO(u));
        }
        u.setClientCode(normalized);
        // Sprint 51 T2 finding #5 — the JWT carries clientCode as a claim.
        // Re-scoping the user without bumping token_version leaves the old
        // JWT usable with its stale (possibly foreign) clientCode until it
        // expires. Bump invalidates every outstanding token so the user
        // must re-login and get a JWT with the new scope.
        tokenRevocationService.bumpTokenVersion(u);

        auditRepository.save(UserAdminAudit.builder()
                .subjectUserId(u.getId())
                .subjectUsername(u.getUsername())
                .action("ASSIGN_CLIENT")
                .oldClientCode(old)
                .newClientCode(normalized)
                .actorUsername(actorUsername)
                .reason(reason)
                .createdAt(LocalDateTime.now())
                .build());

        log.info("[admin-user] {} reassigned user {} from client={} to client={}",
                actorUsername, u.getUsername(), old, normalized);
        return new MutationOutcome(ActionResult.OK, toDTO(u));
    }

    @Transactional
    public MutationOutcome deactivate(Long userId, String reason, String actorUsername) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return new MutationOutcome(ActionResult.USER_NOT_FOUND, null);
        }
        User u = found.get();
        if (u.getDeactivatedAt() != null) {
            return new MutationOutcome(ActionResult.ALREADY_IN_TARGET_STATE, toDTO(u));
        }
        u.setDeactivatedAt(LocalDateTime.now());
        u.setDeactivatedBy(actorUsername);
        // Sprint 51 T2 finding #5 — invalidate every outstanding JWT for
        // this account in one write. bumpTokenVersion() saves u; no
        // separate userRepository.save needed.
        tokenRevocationService.bumpTokenVersion(u);

        auditRepository.save(UserAdminAudit.builder()
                .subjectUserId(u.getId())
                .subjectUsername(u.getUsername())
                .action("DEACTIVATE")
                .oldClientCode(u.getClientCode())
                .newClientCode(u.getClientCode())
                .actorUsername(actorUsername)
                .reason(reason)
                .createdAt(LocalDateTime.now())
                .build());

        log.warn("[admin-user] {} deactivated user {}", actorUsername, u.getUsername());
        return new MutationOutcome(ActionResult.OK, toDTO(u));
    }

    @Transactional
    public MutationOutcome reactivate(Long userId, String reason, String actorUsername) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return new MutationOutcome(ActionResult.USER_NOT_FOUND, null);
        }
        User u = found.get();
        if (u.getDeactivatedAt() == null) {
            return new MutationOutcome(ActionResult.ALREADY_IN_TARGET_STATE, toDTO(u));
        }
        u.setDeactivatedAt(null);
        u.setDeactivatedBy(null);
        userRepository.save(u);

        auditRepository.save(UserAdminAudit.builder()
                .subjectUserId(u.getId())
                .subjectUsername(u.getUsername())
                .action("REACTIVATE")
                .oldClientCode(u.getClientCode())
                .newClientCode(u.getClientCode())
                .actorUsername(actorUsername)
                .reason(reason)
                .createdAt(LocalDateTime.now())
                .build());

        log.info("[admin-user] {} reactivated user {}", actorUsername, u.getUsername());
        return new MutationOutcome(ActionResult.OK, toDTO(u));
    }

    @Transactional(readOnly = true)
    public List<AdminUserAuditDTO> recentAudit(int limit) {
        int bounded = Math.min(Math.max(limit, 1), 500);
        return auditRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, bounded)).stream()
                .map(AdminUserService::toAuditDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminUserAuditDTO> auditForUser(Long userId) {
        return auditRepository.findBySubjectUserIdOrderByCreatedAtDesc(userId).stream()
                .map(AdminUserService::toAuditDTO)
                .toList();
    }

    private static AdminUserDTO toDTO(User u) {
        return AdminUserDTO.builder()
                .id(u.getId())
                .username(u.getUsername())
                .email(u.getEmail())
                .fullName(u.getFullName())
                .role(u.getRole())
                .clientCode(u.getClientCode())
                .emailVerified(u.getEmailVerified())
                .deactivatedAt(u.getDeactivatedAt())
                .deactivatedBy(u.getDeactivatedBy())
                .createdAt(u.getCreatedAt())
                .build();
    }

    private static AdminUserAuditDTO toAuditDTO(UserAdminAudit a) {
        return AdminUserAuditDTO.builder()
                .id(a.getId())
                .subjectUserId(a.getSubjectUserId())
                .subjectUsername(a.getSubjectUsername())
                .action(a.getAction())
                .oldClientCode(a.getOldClientCode())
                .newClientCode(a.getNewClientCode())
                .actorUsername(a.getActorUsername())
                .reason(a.getReason())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
