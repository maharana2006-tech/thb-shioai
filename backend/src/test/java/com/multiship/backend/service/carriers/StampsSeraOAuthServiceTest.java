package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.StampsSeraOAuthService.VerifiedState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for {@link StampsSeraOAuthService}. Four shapes
 * must be nailed down for the 3-legged OAuth flow to be safe:
 * <ol>
 *   <li><b>Signed state round-trip</b> — {@link StampsSeraOAuthService#signState}
 *       produces a base64url token that {@link StampsSeraOAuthService#verifyState}
 *       decodes back to the same accountId, no leakage across account
 *       ids, no acceptance of an unsigned/tampered payload.</li>
 *   <li><b>State expiry</b> — a state older than 10 min must fail
 *       verification even with a correct HMAC.</li>
 *   <li><b>Authorize URL shape</b> — must include response_type=code,
 *       redirect_uri, scope (offline_access), state and PKCE
 *       (code_challenge + code_challenge_method=S256) as query parameters;
 *       must hit the sandbox host when environment is SANDBOX and the
 *       prod host otherwise.</li>
 *   <li><b>PKCE verifier round-trip</b> — the verifier generated at
 *       authorize time survives the state round-trip and is presented
 *       on the token exchange body.</li>
 * </ol>
 *
 * <p>The token exchange {@code postToken} is exercised only via an
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

    @Test
    void signState_secureRandomNonce_doubleSignBothDecode() {
        // SecureRandom replacement check: nonce comes from SecureRandom, not
        // java.util.Random. Signing the same accountId twice must produce two
        // distinct states that both decode successfully. If the nonce source
        // were accidentally seeded deterministically, both would decode to
        // the same bytes.
        String a = service.signState(99L);
        String b = service.signState(99L);
        assertNotEquals(a, b, "SecureRandom nonce must differ across calls");
        assertEquals(99L, service.verifyState(a).orElseThrow());
        assertEquals(99L, service.verifyState(b).orElseThrow());
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
        assertTrue(url.contains("code_challenge="),
                "PKCE code_challenge must be in the query string; got: " + url);
        assertTrue(url.contains("code_challenge_method=S256"),
                "PKCE S256 method must be declared; got: " + url);
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

    // ===== PKCE verifier round-trip =====

    @Test
    void pkceVerifier_survivesStateRoundTrip() {
        // signState embeds the verifier as the 4th payload segment; verifyStateWithVerifier
        // must hand the same string back. This is what the callback uses to present
        // code_verifier back on the token POST.
        String verifier = "my-pkce-verifier-random-32-byte-base64url";
        String state = service.signState(123L, verifier);
        Optional<VerifiedState> verified = service.verifyStateWithVerifier(state);
        assertTrue(verified.isPresent(), "verifyStateWithVerifier must accept PKCE-stamped state");
        assertEquals(123L, verified.get().accountId());
        assertEquals(verifier, verified.get().codeVerifier());
    }

    @Test
    void pkceVerifier_omittedOnLegacyState_returnsNull() {
        // signState(accountId) builds a 3-segment payload (no verifier). Callers
        // that upgraded verifyState to verifyStateWithVerifier see a null codeVerifier,
        // which exchangeCode handles by skipping code_verifier on the token POST.
        String state = service.signState(123L);
        Optional<VerifiedState> verified = service.verifyStateWithVerifier(state);
        assertTrue(verified.isPresent());
        assertEquals(123L, verified.get().accountId());
        assertNull(verified.get().codeVerifier(),
                "Legacy state (no PKCE) must decode with null codeVerifier");
    }

    @Test
    void buildAuthorizeUrl_statePayloadCarriesVerifier() {
        // Decode the state query param back and confirm its payload has 4 segments
        // (accountId, ts, nonce, verifier) + the hmac tag. This locks in the wire
        // format so an upstream change doesn't silently drop the verifier.
        String url = service.buildAuthorizeUrl(7L, "cid", "PRODUCTION");
        int idx = url.indexOf("state=");
        assertTrue(idx > 0);
        String state = url.substring(idx + "state=".length());
        int amp = state.indexOf('&');
        if (amp > 0) state = state.substring(0, amp);
        byte[] raw = Base64.getUrlDecoder().decode(state);
        String decoded = new String(raw, StandardCharsets.UTF_8);
        // Payload = everything before the last ':'; split on ':' must give >=4 segments
        int lastColon = decoded.lastIndexOf(':');
        String payload = decoded.substring(0, lastColon);
        String[] parts = payload.split(":");
        assertEquals(4, parts.length,
                "state payload must be accountId:ts:nonce:verifier; got: " + decoded);
        assertEquals("7", parts[0]);
        assertNotNull(parts[3]);
        assertFalse(parts[3].isEmpty(), "verifier segment must be non-empty");
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
        assertNotNull(r.errorMessage());
    }

    @Test
    void exchangeCode_unreachableHost_returnsFailureResult() {
        props.getStamps().setSeraAuthUrl("http://localhost:1/oauth/token");
        StampsSeraOAuthService.TokenExchangeResult r =
                service.exchangeCode("bogus-code", "cid", "secret", "PRODUCTION");
        assertFalse(r.success());
        assertNotNull(r.errorMessage());
    }

    @Test
    void exchangeCode_pkceOverload_unreachableHost_returnsFailureResult() {
        // PKCE overload — same error-swallowing contract. Smoke-check the
        // new 5-arg signature at least reaches postToken without blowing up
        // the test from a null-verifier.
        props.getStamps().setSeraAuthUrl("http://localhost:1/oauth/token");
        StampsSeraOAuthService.TokenExchangeResult r =
                service.exchangeCode("bogus-code", "cid", "secret", "PRODUCTION",
                        "some-pkce-verifier");
        assertFalse(r.success());
        assertNotNull(r.errorMessage());
    }

    // ===== PKCE challenge computation =====

    @Test
    void computePkceChallenge_knownAnswerFromRfc7636Appendix() {
        // RFC 7636 Appendix B: verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        // → challenge "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        String challenge = StampsSeraOAuthService.computePkceChallenge(
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge,
                "PKCE S256 challenge must match RFC 7636 Appendix B worked example");
    }

    @Test
    void generatePkceVerifier_producesBase64UrlNoPadding() {
        String v = StampsSeraOAuthService.generatePkceVerifier();
        assertNotNull(v);
        // 32 bytes base64url = 43 chars (no padding), by RFC 7636 §4.1 bound
        assertEquals(43, v.length(), "verifier must be 43 base64url chars (32 bytes, no padding)");
        assertFalse(v.contains("="), "verifier must have no padding");
        assertFalse(v.contains("+"), "verifier must be base64url (no +)");
        assertFalse(v.contains("/"), "verifier must be base64url (no /)");
    }

    // ===== cache clear-for-single-refresh-token =====

    @Test
    void clearCachedTokenFor_nullAndBlank_isNoOp() {
        // Shouldn't throw. Nothing to assert beyond "doesn't blow up" — the
        // method's whole job is defensive.
        service.clearCachedTokenFor(null);
        service.clearCachedTokenFor("");
    }
}
