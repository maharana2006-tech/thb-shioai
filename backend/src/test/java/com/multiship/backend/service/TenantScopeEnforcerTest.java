package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantScopeEnforcerTest {

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void setCaller(String username, String clientCode, String role) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = User.withUsername(username).password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        if (clientCode != null) {
            token.setDetails(new JwtAuthenticationFilter.AuthDetails(clientCode));
        }
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void operator_clampReturnsRequestedAsIs() {
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(true));
        setCaller("admin", null, "ADMIN");
        // null → null (no filter); explicit value → value
        assertEquals(null, enforcer.clampClientCode(null));
        assertEquals("ACME", enforcer.clampClientCode("ACME"));
        assertTrue(enforcer.resolveScope().isEmpty());
    }

    @Test
    void scopedCaller_nullFilterClampsToOwnClient() {
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(true));
        setCaller("acmeuser", "ACME", "USER");
        assertEquals("ACME", enforcer.clampClientCode(null));
        assertEquals(Optional.of("ACME"), enforcer.resolveScope());
    }

    @Test
    void scopedCaller_matchingFilterIsAccepted() {
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(true));
        setCaller("acmeuser", "ACME", "USER");
        assertEquals("ACME", enforcer.clampClientCode("acme")); // case-insensitive
    }

    @Test
    void scopedCaller_foreignFilterIsRejected() {
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(true));
        setCaller("acmeuser", "ACME", "USER");
        assertThrows(AccessDeniedException.class,
                () -> enforcer.clampClientCode("OTHER"));
    }

    @Test
    void requireTenantMatch_operator_isSilent() {
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(true));
        setCaller("admin", null, "ADMIN");
        enforcer.requireTenantMatch("ANYTHING");
        enforcer.requireTenantMatch(null); // even null-tenant rows OK for admin
    }

    @Test
    void requireTenantMatch_scopedCaller_matchingRowOK() {
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(true));
        setCaller("acmeuser", "ACME", "USER");
        enforcer.requireTenantMatch("ACME");
        enforcer.requireTenantMatch("acme");
    }

    @Test
    void requireTenantMatch_scopedCaller_foreignRowThrows() {
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(true));
        setCaller("acmeuser", "ACME", "USER");
        assertThrows(AccessDeniedException.class,
                () -> enforcer.requireTenantMatch("OTHER"));
        assertThrows(AccessDeniedException.class,
                () -> enforcer.requireTenantMatch(null));
    }

    @Test
    void flagOff_userClampBehavesAsOperator() {
        // With the flag off, a USER-with-clientCode is still an operator.
        // This is the deploy-day behavior that keeps everything working.
        var enforcer = new TenantScopeEnforcer(new AccessScopePolicy(false));
        setCaller("acmeuser", "ACME", "USER");
        assertEquals("OTHER", enforcer.clampClientCode("OTHER"));
        assertEquals(null, enforcer.clampClientCode(null));
    }
}
