package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR ω — regression tests for {@link FedExConnector#describeFedExAuthError}.
 * The Verify button on /settings/carriers previously surfaced only the
 * catch-all "Unable to obtain FedEx access token." for every failure
 * mode (invalid key, sandbox-key-on-prod, unapproved production app,
 * OAuth outage) — see project_fedex_env_routing.md follow-up. These
 * tests document the specific messages the new describer produces so
 * FE localisation stays stable and future FedEx error-code additions
 * fail loud if the mapping regresses.
 *
 * <p>Reflection: {@code describeFedExAuthError} is private (only
 * consumed via {@link FedExConnector#getAccessToken}'s catch branch),
 * so we invoke it directly rather than round-tripping through a mock
 * RestClient.
 */
class FedExAuthErrorDescriberTest {

    private FedExConnector connector;
    private Method describe;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setApiBaseUrl("https://apis.fedex.com");
        props.getFedEx().setSandboxUrl("https://apis-sandbox.fedex.com");
        props.getFedEx().setAuthUrl("https://apis.fedex.com/oauth/token");
        props.getFedEx().setTokenPath("/oauth/token");
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        connector = new FedExConnector(props, new ObjectMapper(), noFx());
        describe = FedExConnector.class.getDeclaredMethod(
                "describeFedExAuthError", int.class, String.class, boolean.class);
        describe.setAccessible(true);
    }

    private String describeError(int status, String body, boolean sandbox) throws Exception {
        return (String) describe.invoke(connector, status, body, sandbox);
    }

    private static com.multiship.backend.service.fx.FxRateService noFx() {
        return new com.multiship.backend.service.fx.FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    @Test
    void notAuthorizedErrorNamesEnvironmentAndSandboxProdMismatch() throws Exception {
        // FedEx's most common auth failure — the one this PR was written for.
        // The message MUST name the environment so operators can see whether
        // a sandbox key was saved as PRODUCTION.
        String body = "{\"errors\":[{\"code\":\"NOT.AUTHORIZED.ERROR\","
                + "\"message\":\"Not authorized\"}],\"transactionId\":\"abc\"}";
        String msg = describeError(401, body, false);
        assertNotNull(msg);
        assertTrue(msg.contains("NOT.AUTHORIZED.ERROR"),
                "message must include the FedEx error code so ops can grep for it: " + msg);
        assertTrue(msg.contains("PRODUCTION"),
                "message must name the target environment: " + msg);
        assertTrue(msg.contains("apis.fedex.com"),
                "message must name the target OAuth host so operators can "
                        + "spot-check what environment the account is on: " + msg);
        assertTrue(msg.toLowerCase().contains("sandbox key"),
                "must mention that sandbox key fails against production: " + msg);
        assertTrue(msg.toLowerCase().contains("approved for production"),
                "must mention the FedEx portal PROD approval step (the "
                        + "most common cause of a 401 with valid-looking creds): " + msg);
    }

    @Test
    void notAuthorizedFromSandboxHostNamesSandboxUrl() throws Exception {
        String body = "{\"errors\":[{\"code\":\"NOT.AUTHORIZED.ERROR\","
                + "\"message\":\"Not authorized\"}]}";
        String msg = describeError(401, body, true);
        assertTrue(msg.contains("SANDBOX"), msg);
        assertTrue(msg.contains("apis-sandbox.fedex.com"),
                "sandbox failures must name the sandbox host so ops see "
                        + "they've routed to the right place: " + msg);
    }

    @Test
    void unauthorizedCredentialApplicationHintsPortalScopeFix() throws Exception {
        // "app doesn't have this API enabled" — different fix from a bad key
        // (portal step, not a code fix).
        String body = "{\"errors\":[{\"code\":\"UNAUTHORIZED.CREDENTIAL.APPLICATION\","
                + "\"message\":\"Application not authorized\"}]}";
        String msg = describeError(401, body, false);
        assertTrue(msg.contains("UNAUTHORIZED.CREDENTIAL.APPLICATION")
                        || msg.toLowerCase().contains("api"),
                "message must call out the credential-scope issue: " + msg);
        assertTrue(msg.toLowerCase().contains("developer portal"),
                "must point ops at the FedEx Developer Portal fix: " + msg);
    }

    @Test
    void systemUnexpectedErrorFlagsFedExOutage() throws Exception {
        String body = "{\"errors\":[{\"code\":\"SYSTEM.UNEXPECTED.ERROR\","
                + "\"message\":\"Internal error\"}]}";
        String msg = describeError(500, body, false);
        assertTrue(msg.contains("SYSTEM.UNEXPECTED.ERROR"), msg);
        assertTrue(msg.toLowerCase().contains("retry")
                        || msg.toLowerCase().contains("transient"),
                "must guide ops to retry rather than assume creds are wrong: " + msg);
    }

    @Test
    void unrecognisedCodeStillIncludesHttpStatusAndCode() throws Exception {
        // Guard-rail: any future FedEx error code we don't have a hand-tuned
        // message for must still produce a message that names the status,
        // code, and human message — never fall through to a generic
        // "Unable to obtain FedEx access token." Regression check on the
        // very bug this PR fixes.
        String body = "{\"errors\":[{\"code\":\"SOME.NEW.CODE\","
                + "\"message\":\"Something new\"}]}";
        String msg = describeError(429, body, false);
        assertTrue(msg.contains("429"), msg);
        assertTrue(msg.contains("SOME.NEW.CODE"), msg);
        assertTrue(msg.contains("Something new"), msg);
    }

    @Test
    void emptyBodyStillNamesHttpStatusAndEnv() throws Exception {
        // Rare — FedEx returned no body (5xx from an upstream proxy). We
        // must not throw; return a message that at least names the
        // status + env so ops know where to look.
        String msg = describeError(502, "", false);
        assertTrue(msg.contains("502"), msg);
        assertTrue(msg.contains("PRODUCTION"), msg);
    }

    @Test
    void malformedJsonBodyDoesNotThrow() throws Exception {
        // If FedEx returns HTML (rare — bad gateway page), the JSON parse
        // fails silently and we fall through to the generic HTTP-status
        // message. The important assertion is NO exception is thrown from
        // the describer; the previous catch-all took care of that but the
        // new describer branches on parsed JSON.
        String msg = describeError(502, "<html>Bad Gateway</html>", true);
        assertNotNull(msg);
        assertTrue(msg.contains("502"), msg);
    }
}
