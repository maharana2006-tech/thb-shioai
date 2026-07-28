package com.multiship.backend.config;

import com.multiship.backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Audit-fix #8 — the shared SpEL helper backing every tenant-scoped
 * @PreAuthorize expression across the app. Before this PR it was
 * used only by OrderController + CustomFieldController but had zero
 * test coverage. LabelTemplateController now leans on it too, so
 * we lock down the semantics.
 */
class OrderAccessEvaluatorTest {

    private OrderAccessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new OrderAccessEvaluator(mock(OrderRepository.class));
    }

    // ===== canViewTenant =====

    @Test
    void adminSeesAnyTenant() {
        assertTrue(evaluator.canViewTenant(auth("bob", "ROLE_ADMIN"), "ARHDEV"));
        assertTrue(evaluator.canViewTenant(auth("bob", "ROLE_ADMIN"), "COMPETITOR"));
        assertTrue(evaluator.canViewTenant(auth("bob", "ROLE_ADMIN"), null),
                "null tenant is 'platform-wide' — operators pass");
    }

    @Test
    void userSeesAnyTenant() {
        // USER is an operator role in this codebase (see OrderController).
        assertTrue(evaluator.canViewTenant(auth("alice", "ROLE_USER"), "ARHDEV"));
        assertTrue(evaluator.canViewTenant(auth("alice", "ROLE_USER"), "COMPETITOR"));
    }

    @Test
    void tenantSeesOwnTenantOnly() {
        Authentication authn = auth("arhdev", "ROLE_TENANT");
        assertTrue(evaluator.canViewTenant(authn, "ARHDEV"),
                "TENANT sees own tenant (case-insensitive)");
        assertTrue(evaluator.canViewTenant(authn, "arhdev"),
                "TENANT sees own tenant (lower-cased request)");
        assertFalse(evaluator.canViewTenant(authn, "COMPETITOR"),
                "TENANT must NOT see another tenant");
        assertFalse(evaluator.canViewTenant(authn, null),
                "TENANT can't sneak in via null tenant to read platform-default");
    }

    @Test
    void unauthenticatedReturnsFalse() {
        Authentication authn = new AnonymousAuthenticationToken(
                "anon", "anon", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertFalse(evaluator.canViewTenant(authn, "ARHDEV"));
    }

    @Test
    void nullAuthenticationReturnsFalse() {
        assertFalse(evaluator.canViewTenant(null, "ARHDEV"));
    }

    @Test
    void unknownRoleReturnsFalse() {
        // A hypothetical future role that isn't ADMIN / USER / TENANT.
        Authentication authn = auth("eve", "ROLE_AUDITOR");
        assertFalse(evaluator.canViewTenant(authn, "ARHDEV"));
    }

    // ===== helpers =====

    private static Authentication auth(String username, String role) {
        return new UsernamePasswordAuthenticationToken(
                username, "",
                List.of(new SimpleGrantedAuthority(role)));
    }
}
