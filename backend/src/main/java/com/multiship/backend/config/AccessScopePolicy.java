package com.multiship.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sprint 50 Tier 0.5 PR E — single source of truth for "who can see whose
 * tenant." Consulted by {@link OrderAccessEvaluator} SpEL, the service-layer
 * {@link com.multiship.backend.service.TenantScopeEnforcer}, and any
 * {@code @PreAuthorize("@accessScope...")} controller annotation.
 *
 * <h3>Semantic rules</h3>
 * <ul>
 *   <li><b>ADMIN</b> — always a platform operator, sees every tenant.</li>
 *   <li><b>USER</b> — platform operator ONLY when the feature flag
 *       {@code access.scope-user-by-client} is off, OR when the flag is on
 *       but the account has no {@code client_code} (legacy operator kept
 *       for backward-compat during rollout).</li>
 *   <li><b>USER with client_code + flag on</b> — tenant-scoped; sees only
 *       their own client's rows.</li>
 *   <li><b>TENANT</b> — always tenant-scoped; sees only their own rows.
 *       Tenant identity is the {@code AuthDetails.clientCode} attached by
 *       {@link JwtAuthenticationFilter} (falls back to the username-uppercase
 *       convention for pre-PR-A tokens that predate the claim).</li>
 *   <li><b>API</b> ({@link ApiKeyPrincipal}) — tenant-scoped to the key's
 *       {@code clientCode} unless the key is a platform-wide key
 *       ({@code clientCode = "*"}), which behaves as an operator.</li>
 * </ul>
 *
 * <p>The flag defaults to {@code false} so nothing changes on deploy — ops
 * is expected to backfill {@code client_code} on every legacy USER via the
 * {@code /settings/users} admin page, then flip the flag.
 */
@Component("accessScope")
public class AccessScopePolicy {

    private final boolean scopeUserByClient;

    public AccessScopePolicy(
            @Value("${access.scope-user-by-client:false}") boolean scopeUserByClient) {
        this.scopeUserByClient = scopeUserByClient;
    }

    /** True when the operational flag is on (test/observability convenience). */
    public boolean isFlagEnabled() {
        return scopeUserByClient;
    }

    /**
     * The tenant the caller is scoped to, or empty when the caller is a
     * platform operator (org-wide). Callers should treat "empty" as
     * "no client filter applied."
     */
    public Optional<String> tenantOf(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }

        if (hasRole(auth, "ROLE_ADMIN")) {
            return Optional.empty();
        }

        String clientCode = resolveClientCode(auth);

        if (hasRole(auth, "ROLE_TENANT")) {
            // TENANT is always tenant-scoped. Falls back to the username-
            // uppercase convention when the token predates the clientCode
            // claim (PR A backward-compat).
            return Optional.of(clientCode != null && !clientCode.isBlank()
                    ? clientCode.trim().toUpperCase()
                    : auth.getName().trim().toUpperCase());
        }

        if (hasRole(auth, "ROLE_USER")) {
            if (!scopeUserByClient) return Optional.empty(); // legacy operator
            if (clientCode == null || clientCode.isBlank()) return Optional.empty();
            return Optional.of(clientCode.trim().toUpperCase());
        }

        if (hasRole(auth, "ROLE_API")) {
            if (auth.getPrincipal() instanceof ApiKeyPrincipal p) {
                if (p.isAllClients()) return Optional.empty();
                return Optional.ofNullable(p.getClientCode())
                        .map(c -> c.trim().toUpperCase());
            }
            // Rehydrated from JWT bear-token — clientCode carried on details.
            return Optional.ofNullable(clientCode)
                    .filter(c -> !c.isBlank())
                    .map(c -> c.trim().toUpperCase());
        }

        return Optional.empty();
    }

    /**
     * True for callers that see every tenant (ADMIN, USER-without-clientCode
     * when the flag is on, USER when the flag is off, wildcard API key).
     */
    public boolean isPlatformOperator(Authentication auth) {
        return tenantOf(auth).isEmpty()
                && auth != null
                && auth.isAuthenticated()
                && (hasRole(auth, "ROLE_ADMIN")
                        || hasRole(auth, "ROLE_USER")
                        || (hasRole(auth, "ROLE_API")
                                && auth.getPrincipal() instanceof ApiKeyPrincipal p
                                && p.isAllClients()));
    }

    /**
     * SpEL entry point for {@code @PreAuthorize("@accessScope.canAccessTenant(authentication, #clientCode)")}.
     * Operators always pass; tenant-scoped callers pass iff the requested
     * client matches their own (case-insensitive). Unauthenticated / no
     * recognised role always fails — never a silent allow.
     */
    public boolean canAccessTenant(Authentication auth, String requestedClientCode) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (!hasKnownRole(auth)) return false;
        Optional<String> mine = tenantOf(auth);
        if (mine.isEmpty()) return true; // operator
        if (requestedClientCode == null || requestedClientCode.isBlank()) return false;
        return mine.get().equalsIgnoreCase(requestedClientCode.trim());
    }

    private boolean hasKnownRole(Authentication auth) {
        return hasRole(auth, "ROLE_ADMIN")
                || hasRole(auth, "ROLE_USER")
                || hasRole(auth, "ROLE_TENANT")
                || hasRole(auth, "ROLE_API");
    }

    /** Read the clientCode carried by the JWT (via {@link JwtAuthenticationFilter.AuthDetails}). */
    private String resolveClientCode(Authentication auth) {
        Object details = auth.getDetails();
        if (details instanceof JwtAuthenticationFilter.AuthDetails ad) {
            return ad.clientCode();
        }
        return null;
    }

    private boolean hasRole(Authentication auth, String role) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (role.equals(ga.getAuthority())) return true;
        }
        return false;
    }
}
