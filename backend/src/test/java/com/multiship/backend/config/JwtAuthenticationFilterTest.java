package com.multiship.backend.config;

import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Audit-fix #2 — JwtAuthenticationFilter's Sprint 46 branch that
 * rehydrates an ApiKeyPrincipal for OAuth-issued tokens returned null
 * when the underlying ApiKey was missing / deleted / inactive, or the
 * subject was malformed. The filter then constructed
 * {@code UsernamePasswordAuthenticationToken(principal=null, …)},
 * which marks the authentication authenticated=true. Downstream:
 *
 * <ul>
 *   <li>Controllers with {@code @AuthenticationPrincipal ApiKeyPrincipal caller}
 *       received {@code null}; any code that dereferenced the principal
 *       NPE'd.</li>
 *   <li>Method security saw ROLE_API on the authorities and allowed the
 *       request — a revoked key's still-unexpired JWT authenticated as
 *       if the key were live.</li>
 * </ul>
 *
 * The fix: when {@code apiKeyPrincipalFromToken} returns null, skip
 * populating the SecurityContext entirely so the request proceeds
 * unauthenticated (401 downstream).
 */
class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private ApiKeyRepository apiKeyRepository;
    private JwtAuthenticationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        // Real JwtService with a deterministic secret so we can mint tokens.
        jwtService = new JwtService("test-secret-at-least-32-bytes-long-enough-for-hmac", 3_600_000L);
        apiKeyRepository = mock(ApiKeyRepository.class);
        filter = new JwtAuthenticationFilter(jwtService, apiKeyRepository);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeApiKey_populatesApiKeyPrincipal() throws Exception {
        ApiKey key = activeKey(42L);
        when(apiKeyRepository.findById(42L)).thenReturn(Optional.of(key));
        setAuthHeader(jwtService.generateToken("apikey:42", "API"));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "Active API key should authenticate");
        assertInstanceOf(ApiKeyPrincipal.class, auth.getPrincipal());
        assertEquals(42L, ((ApiKeyPrincipal) auth.getPrincipal()).getApiKeyId());
    }

    @Test
    void inactiveApiKey_leavesContextUnauthenticated() throws Exception {
        ApiKey key = activeKey(42L);
        key.setActive(false);
        when(apiKeyRepository.findById(42L)).thenReturn(Optional.of(key));
        setAuthHeader(jwtService.generateToken("apikey:42", "API"));

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Inactive API key must not authenticate — its JWT should be rejected");
    }

    @Test
    void deletedApiKey_leavesContextUnauthenticated() throws Exception {
        when(apiKeyRepository.findById(42L)).thenReturn(Optional.empty());
        setAuthHeader(jwtService.generateToken("apikey:42", "API"));

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Deleted API key must not authenticate");
    }

    @Test
    void malformedSubject_leavesContextUnauthenticated() throws Exception {
        setAuthHeader(jwtService.generateToken("apikey:not-a-number", "API"));

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Malformed apikey subject must not authenticate");
        verifyNoInteractions(apiKeyRepository);  // wasn't even queried
    }

    @Test
    void nonApiRole_stillUsesPlainUserDetails() throws Exception {
        // A normal user token (role=USER) should still populate a plain
        // UserDetails principal even though the apiKeyRepository is wired.
        setAuthHeader(jwtService.generateToken("alice", "USER"));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("alice", auth.getName());
        verifyNoInteractions(apiKeyRepository);
    }

    // ===== helpers =====

    private void setAuthHeader(String token) {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    }

    private static ApiKey activeKey(Long id) {
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setName("test-key");
        k.setClientCode("ARHDEV");
        k.setActive(true);
        k.setScopes("*");
        return k;
    }
}
