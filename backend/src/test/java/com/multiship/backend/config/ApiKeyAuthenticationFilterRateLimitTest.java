package com.multiship.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.service.ApiKeyService;
import com.multiship.backend.service.ratelimit.PublicAuthRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit R2 #340 — verifies the new rate-limit guard on the API-key
 * auth filter. Two scenarios:
 *   1. IP already over the failure cap → 429 short-circuit, bcrypt
 *      never runs.
 *   2. Fresh IP, INVALID token → bcrypt runs (via authenticateDetailed
 *      mock), failure counter incremented on the way out.
 */
class ApiKeyAuthenticationFilterRateLimitTest {

    private ApiKeyService apiKeyService;
    private PublicAuthRateLimiter rateLimiter;
    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        rateLimiter = mock(PublicAuthRateLimiter.class);
        filter = new ApiKeyAuthenticationFilter(apiKeyService, new ObjectMapper(), rateLimiter);
    }

    @Test
    void whenIpOverFailureCap_returns429WithoutRunningBcrypt() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-API-Key")).thenReturn("msk_live_bad_token");
        when(req.getRemoteAddr()).thenReturn("192.0.2.42");
        when(rateLimiter.isOverFailureCap(eq("api-key-auth"), eq("192.0.2.42"))).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertEquals(429, resp.getStatus());
        assertEquals("3600", resp.getHeader("Retry-After"));
        assertTrue(resp.getContentAsString().contains("PUBLIC_AUTH_RATE_LIMITED"));
        // bcrypt path never runs when the limiter shortcuts.
        verify(apiKeyService, never()).authenticateDetailed(anyString());
        // Chain continues so downstream security still returns whatever
        // it would for a request with no auth (but the ApiKey path
        // stopped early — we don't `chain.doFilter` on the 429 path).
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void invalidTokenIncrementsFailureCounter() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-API-Key")).thenReturn("msk_live_bad_token");
        when(req.getRemoteAddr()).thenReturn("192.0.2.99");
        when(rateLimiter.isOverFailureCap(anyString(), anyString())).thenReturn(false);
        when(apiKeyService.authenticateDetailed(anyString()))
                .thenReturn(ApiKeyService.AuthResult.invalid());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        // INVALID path falls through to the chain (Spring's entry point emits 401).
        verify(chain).doFilter(req, resp);
        // Failure recorded so subsequent attempts eventually hit the 429 shortcut.
        verify(rateLimiter).recordFailure("api-key-auth", "192.0.2.99");
    }

    @Test
    void authorizedTokenDoesNotIncrementCounter() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-API-Key")).thenReturn("msk_live_good_token");
        when(req.getRemoteAddr()).thenReturn("192.0.2.7");
        when(rateLimiter.isOverFailureCap(anyString(), anyString())).thenReturn(false);
        ApiKey k = ApiKey.builder().id(1L).name("k").clientCode("ACME")
                .environment("live").keyPrefix("prefix").scopes("shipments")
                .active(true).build();
        when(apiKeyService.authenticateDetailed(anyString()))
                .thenReturn(new ApiKeyService.AuthResult(
                        ApiKeyService.AuthResult.Kind.AUTHORIZED, k, false, null));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        // AUTHORIZED never touches the failure counter — legitimate high-RPS
        // clients aren't punished for the limiter's existence.
        verify(rateLimiter, never()).recordFailure(anyString(), anyString());
    }
}
