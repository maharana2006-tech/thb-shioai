package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.model.AuditLog;
import com.multiship.backend.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 follow-up BS-M3 — {@link AuditService#record} must copy the
 * caller's tenant into the persisted {@code clientCode} column so the
 * list endpoint's repository-layer scope predicate can filter correctly.
 * ADMIN (platform operator) writes leave clientCode NULL — those rows
 * are visible only to platform operators, matching the "system event"
 * semantics.
 */
class AuditServiceScopeTest {

    private final AuditLogRepository repo = mock(AuditLogRepository.class);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void record_populatesClientCode_forTenantScopedUser() {
        authenticateAs("acmeuser", "ACME", "ROLE_USER");
        AuditService svc = wire(new AccessScopePolicy(true));
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.record(AuditService.UPDATE, AuditService.CLIENT,
                42L, "ACME", null, "note");

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(cap.capture());
        assertEquals("ACME", cap.getValue().getClientCode(),
                "USER scoped to ACME must persist clientCode='ACME'");
        assertEquals("acmeuser", cap.getValue().getActor());
    }

    @Test
    void record_leavesClientCodeNull_forAdminOperator() {
        authenticateAs("root", null, "ROLE_ADMIN");
        AuditService svc = wire(new AccessScopePolicy(true));
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.record(AuditService.DELETE, AuditService.CLIENT,
                7L, "ACME", "cross-tenant admin delete");

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(cap.capture());
        assertNull(cap.getValue().getClientCode(),
                "ADMIN is a platform operator — clientCode must stay NULL "
                        + "so the row is visible only to other platform operators.");
    }

    @Test
    void record_leavesClientCodeNull_whenEnforcerNotWired() {
        // No security context, no enforcer — matches a system-initiated
        // event (background job / migration). Row must persist with NULL
        // clientCode without throwing.
        AuditService svc = new AuditService(repo);
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.record(AuditService.CREATE, AuditService.WAREHOUSE,
                1L, "WH1", null, "seed");

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(cap.capture());
        assertNull(cap.getValue().getClientCode());
    }

    private AuditService wire(AccessScopePolicy policy) {
        AuditService svc = new AuditService(repo);
        ReflectionTestUtils.setField(svc, "tenantScope", new TenantScopeEnforcer(policy));
        return svc;
    }

    private static void authenticateAs(String username, String tenant, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        var principal = User.withUsername(username).password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        if (tenant != null) {
            token.setDetails(new JwtAuthenticationFilter.AuthDetails(tenant));
        }
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
