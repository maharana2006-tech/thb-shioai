package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Sprint 50 Tier 0.5 PR E — service-layer companion to
 * {@link AccessScopePolicy}. Encapsulates the two patterns callers need:
 *
 * <ol>
 *   <li><b>Clamp a caller-supplied filter</b>: {@link #clampClientCode(String)}
 *       — a tenant-scoped user asking for {@code ?clientCode=OTHER} either
 *       gets clamped to their own tenant (if they omitted the param) or
 *       rejected with {@code CROSS_TENANT_ACCESS_DENIED} (if they named
 *       a foreign one).</li>
 *   <li><b>Guard a loaded row</b>: {@link #requireTenantMatch(String)} —
 *       after a lookup by ID, verify the loaded row's clientCode matches
 *       the caller's scope, else throw the same error. Prevents IDOR
 *       (e.g. GET /orders/123 when 123 belongs to another tenant).</li>
 * </ol>
 *
 * <p>Both helpers are pass-throughs for platform operators (ADMIN, legacy
 * USER, wildcard API key), so callers can invoke them unconditionally at
 * every service-layer entry point without branching on the caller role.
 */
@Service
@RequiredArgsConstructor
public class TenantScopeEnforcer {

    private final AccessScopePolicy accessScope;

    /** Empty for platform operators; caller's clientCode for tenant-scoped users. */
    public Optional<String> resolveScope() {
        return accessScope.tenantOf(currentAuth());
    }

    /**
     * Given a client-code filter supplied by the caller (may be null),
     * return the effective filter to apply:
     * <ul>
     *   <li>platform operator → returns the caller's filter as-is
     *       (null means "no filter — see all clients");</li>
     *   <li>tenant-scoped caller + null filter → forces the caller's own
     *       clientCode (they see only their own tenant);</li>
     *   <li>tenant-scoped caller + matching filter → returns the caller's
     *       own clientCode (case-normalised);</li>
     *   <li>tenant-scoped caller + foreign filter → throws
     *       {@link AccessDeniedException} tagged {@code CROSS_TENANT_ACCESS_DENIED}.</li>
     * </ul>
     */
    public String clampClientCode(String requested) {
        Optional<String> mine = resolveScope();
        if (mine.isEmpty()) {
            return requested;
        }
        String owner = mine.get();
        if (requested == null || requested.isBlank()) {
            return owner;
        }
        if (!owner.equalsIgnoreCase(requested.trim())) {
            throw crossTenantDenied();
        }
        return owner;
    }

    /**
     * Verify a loaded row's clientCode matches the caller's scope.
     * Silent for platform operators. Throws for tenant-scoped callers whose
     * scope doesn't match the row (including a row with null clientCode,
     * which a tenant-scoped user should never see).
     */
    public void requireTenantMatch(String rowClientCode) {
        Optional<String> mine = resolveScope();
        if (mine.isEmpty()) return;
        if (rowClientCode == null
                || !mine.get().equalsIgnoreCase(rowClientCode.trim())) {
            throw crossTenantDenied();
        }
    }

    /** True when the caller sees every tenant (used to gate admin-only branches). */
    public boolean isPlatformOperator() {
        return accessScope.isPlatformOperator(currentAuth());
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static AccessDeniedException crossTenantDenied() {
        // The ErrorCode is machine-readable; message stays user-facing.
        // Controllers surface this through the existing @ExceptionHandler
        // for AccessDeniedException, which maps to 403.
        return new AccessDeniedException(
                ErrorCode.CROSS_TENANT_ACCESS_DENIED.name()
                        + ": you are not allowed to see rows for this client.");
    }
}
