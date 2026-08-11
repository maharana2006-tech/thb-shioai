package com.multiship.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 50 Tier 0.5 PR E — governs the tenant-scope behavior for every
 * caller class. Each row is one deploy-relevant permutation. Tests exist
 * to lock down the two-mode behavior around the {@code access.scope-user-by-client}
 * flag: OFF (rollout — every USER stays org-wide) and ON (enforced —
 * USER-with-clientCode is tenant-scoped).
 */
class AccessScopePolicyTest {

    private static Authentication userAuth(String username, String clientCode, String role) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = User.withUsername(username).password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        if (clientCode != null) {
            token.setDetails(new JwtAuthenticationFilter.AuthDetails(clientCode));
        }
        return token;
    }

    private static Authentication apiKeyAuth(String clientCode) {
        var principal = new ApiKeyPrincipal(1L, "key", clientCode, Set.of("shipments"));
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_API")));
    }

    @Test
    void adminIsAlwaysPlatformOperator_flagOn() {
        var policy = new AccessScopePolicy(true);
        var auth = userAuth("admin", null, "ADMIN");
        assertTrue(policy.isPlatformOperator(auth));
        assertTrue(policy.tenantOf(auth).isEmpty());
        assertTrue(policy.canAccessTenant(auth, "ANY"));
    }

    @Test
    void adminIsAlwaysPlatformOperator_flagOff() {
        var policy = new AccessScopePolicy(false);
        var auth = userAuth("admin", "ACME", "ADMIN"); // even with clientCode
        assertTrue(policy.isPlatformOperator(auth));
        assertTrue(policy.tenantOf(auth).isEmpty());
    }

    @Test
    void userWithoutClientCode_isOperator_whenFlagOn() {
        var policy = new AccessScopePolicy(true);
        var auth = userAuth("legacyop", null, "USER");
        assertTrue(policy.isPlatformOperator(auth));
        assertTrue(policy.tenantOf(auth).isEmpty());
    }

    @Test
    void userWithClientCode_isTenantScoped_whenFlagOn() {
        var policy = new AccessScopePolicy(true);
        var auth = userAuth("acmeuser", "ACME", "USER");
        assertFalse(policy.isPlatformOperator(auth));
        assertEquals(Optional.of("ACME"), policy.tenantOf(auth));
        assertTrue(policy.canAccessTenant(auth, "acme")); // case-insensitive
        assertFalse(policy.canAccessTenant(auth, "OTHER"));
    }

    @Test
    void userWithClientCode_isOperator_whenFlagOff() {
        // Flag off preserves the pre-PR-E behavior: USER is always org-wide.
        var policy = new AccessScopePolicy(false);
        var auth = userAuth("acmeuser", "ACME", "USER");
        assertTrue(policy.isPlatformOperator(auth));
        assertTrue(policy.tenantOf(auth).isEmpty());
    }

    @Test
    void tenantRoleIsAlwaysScoped_regardlessOfFlag() {
        var policyOn = new AccessScopePolicy(true);
        var policyOff = new AccessScopePolicy(false);
        var auth = userAuth("acmerep", "ACME", "TENANT");
        assertEquals(Optional.of("ACME"), policyOn.tenantOf(auth));
        assertEquals(Optional.of("ACME"), policyOff.tenantOf(auth));
    }

    @Test
    void tenantRole_fallsBackToUsernameConvention_whenNoClientCodeClaim() {
        // Pre-PR-A tokens: no clientCode on Authentication.details.
        var policy = new AccessScopePolicy(true);
        var auth = userAuth("acmerep", null, "TENANT");
        assertEquals(Optional.of("ACMEREP"), policy.tenantOf(auth));
    }

    @Test
    void apiKeyPrincipal_isScopedToKeysClient() {
        var policy = new AccessScopePolicy(true);
        var auth = apiKeyAuth("BETA");
        assertEquals(Optional.of("BETA"), policy.tenantOf(auth));
        assertFalse(policy.isPlatformOperator(auth));
        assertTrue(policy.canAccessTenant(auth, "beta"));
        assertFalse(policy.canAccessTenant(auth, "ALPHA"));
    }

    @Test
    void wildcardApiKey_isPlatformOperator() {
        var policy = new AccessScopePolicy(true);
        var auth = apiKeyAuth("*");
        assertTrue(policy.isPlatformOperator(auth));
        assertTrue(policy.tenantOf(auth).isEmpty());
    }

    @Test
    void nullAuth_isNotOperator_andNotScoped() {
        var policy = new AccessScopePolicy(true);
        assertFalse(policy.isPlatformOperator(null));
        assertTrue(policy.tenantOf(null).isEmpty());
        // canAccessTenant never silently allows unauthenticated calls,
        // even though tenantOf(null) is empty — defence in depth against
        // a controller that mistakenly composes only this SpEL expression
        // without an outer isAuthenticated / hasAnyRole guard.
        assertFalse(policy.canAccessTenant(null, "ACME"));
    }

    @Test
    void unknownRole_isRejectedFromCanAccessTenant() {
        var policy = new AccessScopePolicy(true);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_MYSTERY"));
        var principal = User.withUsername("eve").password("").authorities(authorities).build();
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        assertFalse(policy.canAccessTenant(auth, "ACME"));
    }

    @Test
    void canAccessTenant_scopedCaller_requiresExplicitClient() {
        var policy = new AccessScopePolicy(true);
        var auth = userAuth("acmeuser", "ACME", "USER");
        // Null / blank requested client from a tenant-scoped caller means
        // "unspecified" — cannot be allowed (would leak everything).
        assertFalse(policy.canAccessTenant(auth, null));
        assertFalse(policy.canAccessTenant(auth, "  "));
    }
}
