package com.multiship.backend.controller;

import com.multiship.backend.config.JwtService;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.service.ApiKeyService;
import com.multiship.backend.service.ratelimit.AuthFailureLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for the OAuth 2.0 client-credentials
 * token endpoint. Covers the RFC-6749 error branches (unsupported_grant,
 * invalid_request, invalid_client, rate_limited) + the happy JWT
 * mint path.
 */
class OAuthControllerTest {

    private ApiKeyService apiKeyService;
    private JwtService jwtService;
    private AuthFailureLimiter authFailureLimiter;
    private OAuthController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        jwtService = mock(JwtService.class);
        authFailureLimiter = mock(AuthFailureLimiter.class);
        controller = new OAuthController(apiKeyService, jwtService, authFailureLimiter);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
    }

    private static MultiValueMap<String, String> form(String grantType, String clientId, String secret) {
        LinkedMultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        if (grantType != null) f.add("grant_type", grantType);
        if (clientId != null) f.add("client_id", clientId);
        if (secret != null) f.add("client_secret", secret);
        return f;
    }

    @Test
    void token_missingGrantType_returns400UnsupportedGrant() {
        ResponseEntity<?> resp = controller.token(form(null, "cid", "csec"), null, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("unsupported_grant_type", body.get("error"));
    }

    @Test
    void token_wrongGrantType_returns400UnsupportedGrant() {
        ResponseEntity<?> resp = controller.token(form("password", "cid", "csec"), null, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("unsupported_grant_type", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void token_missingClientCredentials_returns400InvalidRequest() {
        ResponseEntity<?> resp = controller.token(form("client_credentials", null, null), null, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("invalid_request", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void token_lockedOut_returns429RateLimitedBeforeVerifier() {
        when(authFailureLimiter.isLocked(anyString(), eq("cid"))).thenReturn(true);
        when(authFailureLimiter.retryAfterSeconds(anyString(), eq("cid"))).thenReturn(600);

        ResponseEntity<?> resp = controller.token(
                form("client_credentials", "cid", "csec"), null, request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("rate_limited", body.get("error"));
        // Verify the verifier was NOT invoked (fast fail before bcrypt).
        verify(apiKeyService, org.mockito.Mockito.never()).authenticate(anyString());
    }

    @Test
    void token_badSecret_returns401InvalidClientAndRecordsFailure() {
        when(authFailureLimiter.isLocked(anyString(), anyString())).thenReturn(false);
        when(apiKeyService.authenticate(anyString())).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.token(
                form("client_credentials", "cid", "wrong"), null, request);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("invalid_client", ((Map<?, ?>) resp.getBody()).get("error"));
        verify(authFailureLimiter).recordFailure(anyString(), eq("cid"));
    }

    @Test
    void token_valid_returns200JwtBearerAndClearsLimiter() {
        when(authFailureLimiter.isLocked(anyString(), anyString())).thenReturn(false);
        ApiKey key = ApiKey.builder().id(7L).name("k").clientCode("ACME")
                .environment("live").keyPrefix("aaaa").scopes("shipments rates").active(true).build();
        // First env attempt is "live"; return present so the loop stops there.
        when(apiKeyService.authenticate(anyString())).thenReturn(Optional.of(key));
        when(jwtService.generateToken(eq("apikey:7"), eq("API"), eq("ACME"))).thenReturn("jwt-abc");

        ResponseEntity<?> resp = controller.token(
                form("client_credentials", "cid", "csec"), null, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // Response is a TokenResponse; assert via toString / getter reflection avoided by cast.
        OAuthController.TokenResponse tr = (OAuthController.TokenResponse) resp.getBody();
        assertEquals("jwt-abc", tr.getAccessToken());
        assertEquals("Bearer", tr.getTokenType());
        assertTrue(tr.getExpiresIn() > 0);
        verify(authFailureLimiter).recordSuccess(anyString(), eq("cid"));
    }

    @Test
    void token_jsonBody_alsoAcceptedAsFormFallback() {
        // No form params; rely on the JSON body path.
        OAuthController.TokenRequest body = OAuthController.TokenRequest.builder()
                .grantType("client_credentials").clientId("cid").clientSecret("csec").build();
        when(authFailureLimiter.isLocked(anyString(), anyString())).thenReturn(false);
        when(apiKeyService.authenticate(anyString())).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.token(new LinkedMultiValueMap<>(), body, request);

        // Should fall through to the verifier (not the 400 branch), then 401.
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void token_xForwardedFor_isPreferredOverRemoteAddr() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(authFailureLimiter.isLocked(eq("203.0.113.5"), any())).thenReturn(true);
        when(authFailureLimiter.retryAfterSeconds(eq("203.0.113.5"), any())).thenReturn(120);

        ResponseEntity<?> resp = controller.token(
                form("client_credentials", "cid", "csec"), null, request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
    }
}
