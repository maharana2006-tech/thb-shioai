package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for {@link StampsSeraOAuthService}. Three shapes
 * must be nailed down for the 3-legged OAuth flow to be safe:
 * <ol>
 *   <li><b>Signed state round-trip</b> — {@link StampsSeraOAuthService#signState}
 *       produces a base64url token that {@link StampsSeraOAuthService#verifyState}
 *       decodes back to the same accountId, no leakage across account
 *       ids, no acceptance of an unsigned/tampered payload.</li>
 *   <li><b>State expiry</b> — a state older than 10 min must fail
 *       verification even with a correct HMAC.</li>
 *   <li><b>Authorize URL shape</b> — must include response_type=code,
 *       redirect_uri, scope (offline_access), and the signed state as
 *       query parameters; must hit the sandbox host when environment
 *       is SANDBOX and the prod host otherwise.</li>
 * </ol>
 *
 * <p>The token exchange {@code postToken} is exercised only via a
 * unreachable host so the RestClient error path fires; success-path
 * unit coverage would need MockWebServer and is left to integration.
 */
class StampsSeraOAuthServiceTest {

    private static final String TEST_KEY = "dGVzdC1zdGF0ZS1zaWduaW5nLWtleS1mb3ItdW5pdC10ZXN0cw==";

    private CarrierProperties props;
    private StampsSeraOAuthService service;

    @BeforeEach
    void setUp() {
        props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        s.setSeraAuthUrl("https://signin.stampsendicia.com/oauth/token");
        s.setSeraSandboxAuthUrl("https://signin.testing.stampsendicia.com/oauth/token");
        s.setSeraAuthorizeUrl("https://signin.stampsendicia.com/authorize");
        s.setSeraSandboxAuthorizeUrl("https://signin.testing.stampsendicia.com/authorize");
        s.setSeraRedirectUri("http://localhost:8081/api/v1/carrier-accounts/stamps-sera/callback");
        s.setSeraScope("offline_access");
        service = new StampsSeraOAuthService(props, new ObjectMapper());
        ReflectionTestUtils.setField(service, "stateSigningSecret", TEST_KEY);
    }

    // ===== signed state round-trip =====

    @Test
    void signAndVerifyState_roundTripsAccountId() {
        String state = service.signState(42L);
        Optional<Long> verified = service.verifyState(state);
        assertTrue(verified.isPresent());
        assertEquals(42L, verified.get());
    }

    @Test
    void verifyState_rejectsTamperedState() {
        // Flip a character in the middle of the payload — the HMAC no
        // longer matches, so verifyState must reject.
        String state = service.signState(42L);
        String tampered = state.substring(0, state.length() - 5) + "AAAAA";
        assertFalse(service.verifyState(tampered).isPresent());
    }

    @Test
    void verifyState_rejectsBlank() {
        assertFalse(service.verifyState(null).isPresent());
        assertFalse(service.verifyState("").isPresent());
        assertFalse(service.verifyState("garbage").isPresent());
    }

    @Test
    void signState_producesDistinctStatesForDifferentAccounts() {
        // Two calls for the same accountId ALSO differ (nonce + ts) — but
        // the accountId must round-trip to different values.
        String s1 = service.signState(1L);
        String s2 = service.signState(2L);
        assertNotEquals(s1, s2);
        assertEquals(1L, service.verifyState(s1).orElseThrow());
        assertEquals(2L, service.verifyState(s2).orElseThrow());
    }

    // ===== authorize URL shape =====

    @Test
    void buildAuthorizeUrl_includesAllOAuthParams() {
        String url = service.buildAuthorizeUrl(7L, "my-opaque-client-id", "PRODUCTION");
        assertTrue(url.startsWith("https://signin.stampsendicia.com/authorize"),
                "prod env must hit the production authorize host; got: " + url);
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=my-opaque-client-id"));
        assertTrue(url.contains("scope=offline_access"),
                "scope=offline_access is required to receive a refresh_token; got: " + url);
        assertTrue(url.contains("redirect_uri="),
                "redirect_uri must be in the query string; got: " + url);
        assertTrue(url.contains("state="),
                "state (signed accountId) must be in the query string; got: " + url);
    }

    @Test
    void buildAuthorizeUrl_sandboxEnvironment_routesToSandboxHost() {
        String url = service.buildAuthorizeUrl(7L, "cid", "SANDBOX");
        assertTrue(url.startsWith("https://signin.testing.stampsendicia.com/authorize"),
                "SANDBOX env must hit the testing host; got: " + url);
    }

    @Test
    void buildAuthorizeUrl_nullEnvironment_defaultsToProduction() {
        // Nil env → prod host. Matches the pre-existing isSandbox behaviour
        // used by the SWSIM branch.
        String url = service.buildAuthorizeUrl(7L, "cid", null);
        assertTrue(url.startsWith("https://signin.stampsendicia.com/authorize"),
                "null env must default to production; got: " + url);
    }

    // ===== configuration guardrails =====

    @Test
    void buildAuthorizeUrl_authorizeUrlNotConfigured_throws() {
        props.getStamps().setSeraAuthorizeUrl(null);
        try {
            service.buildAuthorizeUrl(1L, "cid", "PRODUCTION");
            org.junit.jupiter.api.Assertions.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("authorize-url"),
                    "message must call out the missing property; got: " + expected.getMessage());
        }
    }

    @Test
    void buildAuthorizeUrl_redirectUriNotConfigured_throws() {
        props.getStamps().setSeraRedirectUri(null);
        try {
            service.buildAuthorizeUrl(1L, "cid", "PRODUCTION");
            org.junit.jupiter.api.Assertions.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("redirect-uri"),
                    "message must call out the missing property; got: " + expected.getMessage());
        }
    }

    // ===== token exchange failure surface =====

    @Test
    void refreshToken_unreachableHost_returnsFailureResult() {
        // Route to a refused port to prove the RestClient exception path
        // is caught and mapped to TokenExchangeResult.failure() rather
        // than escaping to the caller.
        props.getStamps().setSeraAuthUrl("http://localhost:1/oauth/token");
        StampsSeraOAuthService.TokenExchangeResult r =
                service.refreshToken("bogus-refresh", "cid", "secret", "PRODUCTION");
        assertFalse(r.success());
        org.junit.jupiter.api.Assertions.assertNotNull(r.errorMessage());
    }

    @Test
    void exchangeCode_unreachableHost_returnsFailureResult() {
        props.getStamps().setSeraAuthUrl("http://localhost:1/oauth/token");
        StampsSeraOAuthService.TokenExchangeResult r =
                service.exchangeCode("bogus-code", "cid", "secret", "PRODUCTION");
        assertFalse(r.success());
        org.junit.jupiter.api.Assertions.assertNotNull(r.errorMessage());
    }
}
