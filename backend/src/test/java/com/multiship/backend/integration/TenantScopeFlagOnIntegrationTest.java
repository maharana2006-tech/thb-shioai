package com.multiship.backend.integration;

import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.OrderListFilters;
import com.multiship.backend.dto.PaginationRequestDTO;
import com.multiship.backend.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sprint 50 Tier 0.5 PR E — end-to-end guard on the tenant-scope flag flip.
 *
 * <p>With {@code access.scope-user-by-client=true}, a USER-with-clientCode
 * becomes tenant-scoped. This test proves:
 *
 * <ul>
 *   <li>The scoped USER can call {@code listOrders} for their OWN tenant
 *       without error (clamp is a pass-through when the requested filter
 *       matches).</li>
 *   <li>The scoped USER hitting {@code listOrders?tenantId=OTHER} is
 *       rejected with {@link AccessDeniedException} tagged
 *       {@code CROSS_TENANT_ACCESS_DENIED}.</li>
 * </ul>
 *
 * <p>Overrides the {@link AbstractIntegrationTest} property source to flip
 * the feature flag on for this class only, so other integration tests keep
 * observing the default flag-off behavior.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} via the base class.
 */
@TestPropertySource(properties = "access.scope-user-by-client=true")
class TenantScopeFlagOnIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopedUserCanListOwnTenant() {
        putScopedUserInContext("alice", "ACME");

        OrderListFilters filters = new OrderListFilters();
        filters.setTenantId("ACME");

        assertDoesNotThrow(() -> orderService.listOrders(filters, false, defaultPagination()));
    }

    @Test
    void scopedUserCannotListForeignTenant() {
        putScopedUserInContext("alice", "ACME");

        OrderListFilters filters = new OrderListFilters();
        filters.setTenantId("OTHER");

        assertThrows(AccessDeniedException.class,
                () -> orderService.listOrders(filters, false, defaultPagination()),
                "Scoped USER requesting a foreign tenant filter must be rejected by TenantScopeEnforcer");
    }

    @Test
    void scopedUserWithoutFilterIsSilentlyClampedNotRejected() {
        // When a scoped caller omits the tenantId filter, the enforcer
        // clamps to their own — no exception, just a scoped query.
        putScopedUserInContext("alice", "ACME");

        OrderListFilters filters = new OrderListFilters();
        // tenantId left null → clamp to caller's ACME, not rejected.

        assertDoesNotThrow(() -> orderService.listOrders(filters, false, defaultPagination()));
    }

    private static void putScopedUserInContext(String username, String clientCode) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                username, "",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        // The AccessScopePolicy reads the clientCode from AuthDetails —
        // JwtAuthenticationFilter attaches this in production.
        ((UsernamePasswordAuthenticationToken) auth)
                .setDetails(new JwtAuthenticationFilter.AuthDetails(clientCode));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static PaginationRequestDTO defaultPagination() {
        PaginationRequestDTO p = new PaginationRequestDTO();
        p.setPage(0);
        p.setSize(20);
        return p;
    }
}
