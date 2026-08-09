package com.multiship.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.method.HandlerMethod;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR B — locks in the scope-enforcement contract.
 *
 * <p>Every assertion here is a boundary the plan promised: 403 with
 * INSUFFICIENT_SCOPE for missing scope, pass-through for wildcard,
 * pass-through for operator JWTs, no-op for handlers without the
 * annotation.
 */
class ApiKeyScopeInterceptorTest {

    private ApiKeyScopeInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new ApiKeyScopeInterceptor(new ObjectMapper());
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
        SecurityContextHolder.clearContext();
    }

    /* -------- fixture: a fake controller with a scoped + an unscoped method -------- */

    static class TestController {
        @RequiresScope(ApiKeyScope.SHIPMENTS)
        public void scoped() {}

        public void unscoped() {}
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method m = TestController.class.getMethod(methodName);
        return new HandlerMethod(new TestController(), m);
    }

    /* -------- annotation resolution -------- */

    @Test
    void unscopedHandlerAlwaysPasses() throws Exception {
        assertTrue(interceptor.preHandle(request, response, handler("unscoped")));
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void nonHandlerMethodPasses() throws Exception {
        // If the handler isn't a controller method (e.g., ResourceHttpRequestHandler),
        // skip enforcement — the interceptor is a no-op.
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    /* -------- ApiKey principal branches -------- */

    @Test
    void apiKeyWithRequiredScopePasses() throws Exception {
        seedApiKey(Set.of("shipments"));
        assertTrue(interceptor.preHandle(request, response, handler("scoped")));
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void apiKeyWithWildcardScopePasses() throws Exception {
        // The '*' token is the platform-wide grant (see ApiKeyPrincipal.hasScope).
        seedApiKey(Set.of("*"));
        assertTrue(interceptor.preHandle(request, response, handler("scoped")));
    }

    @Test
    void apiKeyMissingScopeReturns403WithMachineReadableBody() throws Exception {
        seedApiKey(Set.of("rates", "tracking"));

        boolean proceed = interceptor.preHandle(request, response, handler("scoped"));

        assertFalse(proceed);
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(response).setContentType("application/json");

        String body = responseBody.toString();
        assertTrue(body.contains("\"errorCode\":\"INSUFFICIENT_SCOPE\""),
                "body must include a stable errorCode so integrators can catch it");
        assertTrue(body.contains("\"requiredScope\":\"shipments\""));
        assertTrue(body.contains("\"grantedScopes\":[\"rates\",\"tracking\"]"),
                "granted scopes are sorted (TreeSet) so diffs across runs are stable");
    }

    /* -------- operator-JWT bypass -------- */

    @Test
    void operatorJwtBypassesScopeCheck() throws Exception {
        // A ROLE_USER caller (JWT) has no ApiKeyPrincipal — scope enforcement
        // is an API-key concept only. @PreAuthorize handles role gating for JWTs.
        var operator = User.withUsername("alice").password("").authorities("ROLE_USER").build();
        var authn = new UsernamePasswordAuthenticationToken(operator, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authn);

        assertTrue(interceptor.preHandle(request, response, handler("scoped")));
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void unauthenticatedRequestBypassesScopeCheck() throws Exception {
        // No principal at all — Spring Security's entry point handles 401.
        // Interceptor MUST NOT double-write a 403 body.
        assertTrue(interceptor.preHandle(request, response, handler("scoped")));
        verify(response, never()).setStatus(anyInt());
    }

    /* -------- ApiKeyScope enum sanity -------- */

    @Test
    void enumTokensMatchPersistedVocabulary() {
        // DEFAULT_SCOPES in ApiKeyService must be a superset of the enum tokens
        // callers see — else the mint flow would produce keys that can't call
        // anything with the corresponding annotation.
        Set<String> tokens = Set.of(
                ApiKeyScope.SHIPMENTS.token(),
                ApiKeyScope.RATES.token(),
                ApiKeyScope.TRACKING.token(),
                ApiKeyScope.VOID.token(),
                ApiKeyScope.ADDRESSES.token(),
                ApiKeyScope.PICKUPS.token(),
                ApiKeyScope.LANDED_COST.token());
        // Just assert stable spellings — a rename would break persisted keys.
        assertTrue(tokens.contains("shipments"));
        assertTrue(tokens.contains("rates"));
        assertTrue(tokens.contains("tracking"));
        assertTrue(tokens.contains("void"));
        assertTrue(tokens.contains("addresses"));
        assertTrue(tokens.contains("pickups"));
        assertTrue(tokens.contains("landed-cost"));
    }

    @Test
    void parseTokensHandlesEmptyBlankAndWhitespace() {
        assertEquals(Set.of(), ApiKeyScope.parseTokens(null));
        assertEquals(Set.of(), ApiKeyScope.parseTokens(""));
        assertEquals(Set.of(), ApiKeyScope.parseTokens("   "));
        assertEquals(Set.of("shipments", "rates"),
                ApiKeyScope.parseTokens("shipments  rates"));
    }

    /* -------- helper -------- */

    private void seedApiKey(Set<String> scopes) {
        var principal = new ApiKeyPrincipal(42L, "test-key", "ACME", scopes);
        var authn = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_API")));
        SecurityContextHolder.getContext().setAuthentication(authn);
    }
}
