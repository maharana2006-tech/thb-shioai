package com.multiship.backend.service;

import com.multiship.backend.dto.AdminUserAuditDTO;
import com.multiship.backend.dto.AdminUserDTO;
import com.multiship.backend.model.User;
import com.multiship.backend.model.UserAdminAudit;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.UserAdminAuditRepository;
import com.multiship.backend.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
        ALREADY_IN_TARGET_STATE,
        /** Sprint 55 audit #292 — rejected because deactivating this user
         *  would leave zero active admins (org lockout). */
        LAST_ADMIN_CANNOT_DEACTIVATE,
        /** Sprint 55 audit #293 — rejected because the requested role
         *  transition is not allowed by policy (e.g. ADMIN demotion is
         *  reserved for out-of-band audit recovery). */
        ROLE_TRANSITION_NOT_ALLOWED,
        /** Sprint 55 audit #293 — rejected because the requested role
         *  value isn't recognized (must be USER, TENANT, or ADMIN). */
        UNKNOWN_ROLE
    }

    public record MutationOutcome(ActionResult result, AdminUserDTO user) {}

    /** Sprint 51 BP-L4 — default page size when the caller doesn't specify one. */
    static final int DEFAULT_PAGE_SIZE = 50;
    /** Sprint 51 BP-L4 — hard clamp on page size to bound the DB fetch. */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * Sprint 51 BP-L4 — backwards-compat overload retained for existing
     * unit tests + callers that don't page. Delegates to the paginated
     * variant with the compile-time default size.
     */
    @Transactional(readOnly = true)
    public List<AdminUserDTO> list(String search, String role, String clientCode, Boolean activeOnly) {
        return list(search, role, clientCode, activeOnly, 0, DEFAULT_PAGE_SIZE);
    }

    /**
     * Sprint 51 BP-L4 — paginated list. Pre-BP-L4 the impl called
     * {@code userRepository.findAll()} platform-wide then filtered in
     * Java, so a 50k-user table scanned every row for a single admin
     * page render. Now the filters are pushed to the DB via Specification
     * and the caller pages a bounded slice (default 50, max
     * {@value #MAX_PAGE_SIZE}).
     *
     * @param page 0-based page index; negative clamps to 0.
     * @param size requested page size; capped at {@value #MAX_PAGE_SIZE}
     *             so a caller can never force an unbounded fetch.
     */
    @Transactional(readOnly = true)
    public List<AdminUserDTO> list(String search, String role, String clientCode, Boolean activeOnly,
                                   int page, int size) {
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String q = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        String r = role == null || role.isBlank() ? null : role.trim().toUpperCase(Locale.ROOT);
        String cc = clientCode == null || clientCode.isBlank() ? null : clientCode.trim().toUpperCase(Locale.ROOT);
        Boolean active = activeOnly;

        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>(4);
            if (q != null) {
                String like = "%" + q + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("fullName")), like)));
            }
            if (r != null) {
                preds.add(cb.equal(cb.upper(root.get("role")), r));
            }
            if (cc != null) {
                preds.add(cb.equal(cb.upper(root.get("clientCode")), cc));
            }
            if (Boolean.TRUE.equals(active)) {
                preds.add(cb.isNull(root.get("deactivatedAt")));
            }
            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };

        return userRepository.findAll(spec,
                        PageRequest.of(boundedPage, boundedSize, Sort.by("username").ascending()))
                .map(AdminUserService::toDTO)
                .getContent();
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
        userRepository.save(u);

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

    /**
     * Sprint 55 audit #293 — change a user's role. Allowed transitions per
     * the audit's Option 1 (restricted): USER ↔ TENANT free; USER/TENANT
     * → ADMIN allowed (audit-safe with FE 2FA confirm); ADMIN → anything
     * BLOCKED (out-of-band audit recovery only).
     *
     * <p>The backend enforces the same last-admin quorum from
     * {@link #deactivate} — an ADMIN → USER/TENANT demote would leave
     * zero admins would be rejected here even if the policy were relaxed,
     * so callers always see a coherent error.
     */
    @Transactional
    public MutationOutcome changeRole(Long userId, String requestedRole, String reason,
                                       String actorUsername) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return new MutationOutcome(ActionResult.USER_NOT_FOUND, null);
        }
        String normalized = requestedRole == null ? "" : requestedRole.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("USER", "TENANT", "ADMIN").contains(normalized)) {
            return new MutationOutcome(ActionResult.UNKNOWN_ROLE, null);
        }
        User u = found.get();
        String old = u.getRole() == null ? "" : u.getRole().toUpperCase(Locale.ROOT);
        if (old.equals(normalized)) {
            return new MutationOutcome(ActionResult.ALREADY_IN_TARGET_STATE, toDTO(u));
        }
        // Policy: ADMIN → USER/TENANT is blocked (out-of-band only).
        // Every OTHER transition is permitted.
        if ("ADMIN".equals(old) && !"ADMIN".equals(normalized)) {
            log.warn("[admin-user] {} attempted ADMIN → {} on {} — blocked by policy",
                    actorUsername, normalized, u.getUsername());
            return new MutationOutcome(ActionResult.ROLE_TRANSITION_NOT_ALLOWED, toDTO(u));
        }
        u.setRole(normalized);
        // Bump token_version — role is a JWT claim; stale tokens must not
        // retain the old role's privileges.
        tokenRevocationService.bumpTokenVersion(u);
        userRepository.save(u);

        auditRepository.save(UserAdminAudit.builder()
                .subjectUserId(u.getId())
                .subjectUsername(u.getUsername())
                .action("CHANGE_ROLE")
                .oldClientCode(old)  // reuse the free-form field to record old→new role
                .newClientCode(normalized)
                .actorUsername(actorUsername)
                .reason(reason)
                .createdAt(LocalDateTime.now())
                .build());

        log.warn("[admin-user] {} changed role of {} from {} to {}",
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
        // Sprint 55 audit #292 — last-admin protection.
        // Deactivating the only remaining ADMIN would lock every operator
        // out of admin functions permanently. The FE PR #291 added a
        // client-side check but that's bypassable via direct API + a
        // race window; the backend is the authoritative guard.
        // Ignores case ('ADMIN' vs 'admin') to match how roles are stored.
        if ("ADMIN".equalsIgnoreCase(u.getRole())) {
            long activeAdmins = userRepository.countByRoleIgnoreCaseAndDeactivatedAtIsNull("ADMIN");
            if (activeAdmins <= 1) {
                log.warn("[admin-user] {} attempted to deactivate the last active admin {} — rejected",
                        actorUsername, u.getUsername());
                return new MutationOutcome(ActionResult.LAST_ADMIN_CANNOT_DEACTIVATE, toDTO(u));
            }
        }
        u.setDeactivatedAt(LocalDateTime.now());
        u.setDeactivatedBy(actorUsername);
        // Sprint 51 T2 finding #5 — invalidate every outstanding JWT for
        // this account. bumpTokenVersion mutates the field + local cache;
        // save persists deactivatedAt + tokenVersion in one row write.
        tokenRevocationService.bumpTokenVersion(u);
        userRepository.save(u);

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
