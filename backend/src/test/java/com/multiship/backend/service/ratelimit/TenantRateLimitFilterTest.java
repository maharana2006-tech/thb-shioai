package com.multiship.backend.service.ratelimit;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TenantRateLimitFilterTest {

    private TenantRateLimiter limiter;
    private TenantRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        limiter = new TenantRateLimiter(2); // tiny budget so tests exhaust quickly
        filter = new TenantRateLimitFilter(limiter, new AccessScopePolicy(true));
        ReflectionTestUtils.setField(filter, "enabled", true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setTenantCaller(String username, String clientCode) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername(username).password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails(clientCode));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private void setAdminCaller() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var principal = User.withUsername("admin").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void getRequests_passThroughRegardlessOfPath() throws Exception {
        setTenantCaller("acme", "ACME");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders/42/label");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        assertEquals(200, resp.getStatus());
    }

    @Test
    void unwatchedPath_passesThrough() throws Exception {
        setTenantCaller("acme", "ACME");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/settings/preferences");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    void adminCaller_passesThroughEvenOnWatchedPath() throws Exception {
        setAdminCaller();
        // Even 100 hits should not be denied for an operator.
        for (int i = 0; i < 100; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders/1/label");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(req, resp, chain);
            verify(chain, times(1)).doFilter(req, resp);
        }
    }

    @Test
    void tenantExhausted_writes429WithRetryAfterAndTenantRateLimitedCode() throws Exception {
        setTenantCaller("acme", "ACME");
        // Drain (budget = 2)
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/api/v1/orders/1/label");
            MockHttpServletResponse okResp = new MockHttpServletResponse();
            filter.doFilter(ok, okResp, mock(FilterChain.class));
            assertEquals(200, okResp.getStatus());
        }
        // Third hit → 429
        MockHttpServletRequest denied = new MockHttpServletRequest("POST", "/api/v1/orders/1/label");
        MockHttpServletResponse deniedResp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(denied, deniedResp, chain);

        assertEquals(429, deniedResp.getStatus());
        verify(chain, never()).doFilter(denied, deniedResp);
        String retryAfter = deniedResp.getHeader("Retry-After");
        assertNotNull(retryAfter);
        assertTrue(Integer.parseInt(retryAfter) >= 1);
        String body = deniedResp.getContentAsString();
        assertTrue(body.contains("TENANT_RATE_LIMITED"), "body: " + body);
        assertTrue(body.contains("\"code\":429"), "body: " + body);
        assertTrue(body.contains("retryAfterSeconds"), "body: " + body);
    }

    @Test
    void differentTenants_haveIndependentBuckets() throws Exception {
        // Drain ACME
        setTenantCaller("acme", "ACME");
        for (int i = 0; i < 2; i++) {
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/bulk-labels"),
                    new MockHttpServletResponse(), mock(FilterChain.class));
        }
        MockHttpServletResponse acmeDenied = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/bulk-labels"),
                acmeDenied, mock(FilterChain.class));
        assertEquals(429, acmeDenied.getStatus());

        // OTHER should be fresh
        setTenantCaller("other", "OTHER");
        MockHttpServletResponse otherResp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest otherReq = new MockHttpServletRequest("POST", "/api/v1/bulk-labels");
        filter.doFilter(otherReq, otherResp, chain);
        assertEquals(200, otherResp.getStatus());
        verify(chain, times(1)).doFilter(otherReq, otherResp);
    }

    /* -------- Sprint 51 BS-M2 — AI path uses tighter AI bucket -------- */

    @Test
    void aiPath_isRateLimitedOnItsOwnBucket() throws Exception {
        // Default budget 100, AI budget 2 — a tenant burning AI must NOT
        // exhaust the default bucket and vice versa. Third AI hit → 429.
        limiter = new TenantRateLimiter(100, 2);
        filter = new TenantRateLimitFilter(limiter, new AccessScopePolicy(true));
        ReflectionTestUtils.setField(filter, "enabled", true);

        setTenantCaller("acme", "ACME");

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/ai/parse-address"),
                    ok, mock(FilterChain.class));
            assertEquals(200, ok.getStatus());
        }
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/ai/suggest-hs"),
                denied, mock(FilterChain.class));
        assertEquals(429, denied.getStatus(), "AI bucket exhausted at 2/min");

        // Default write path still has budget — the AI drain didn't leak.
        MockHttpServletResponse writeOk = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders/1/label"),
                writeOk, mock(FilterChain.class));
        assertEquals(200, writeOk.getStatus());
    }

    @Test
    void killSwitchDisabled_neverGates() throws Exception {
        ReflectionTestUtils.setField(filter, "enabled", false);
        setTenantCaller("acme", "ACME");
        // Drain past budget of 2 — all should pass because gate is off.
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders/1/label");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(req, resp, chain);
            assertEquals(200, resp.getStatus());
            verify(chain, times(1)).doFilter(req, resp);
        }
    }
}
