package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@link StampsConnector} SERA flavor branch added
 * to fix the "Verify credentials" flow for accounts on Auctane's newer OAuth
 * 2.0 REST API (host {@code signin.stampsendicia.com}).
 *
 * <p>Origin: the pre-fix {@code getAccessToken} unconditionally required a
 * GUID-shaped {@code IntegrationID} because SWSIM's XML schema enforces it.
 * SERA client_ids are opaque strings (not GUIDs), so any SERA operator saw
 * "USPS via Stamps.com rejected the credentials — the Stamps.com Client ID
 * (IntegrationID) must be a GUID..." and couldn't verify. The SERA branch
 * skips the GUID check and hits the OAuth token endpoint directly.
 *
 * <p>These tests pin the four contract shapes the fix introduces:
 * <ol>
 *   <li>Flavor detection (case-insensitive, whitespace-tolerant, default SWSIM).</li>
 *   <li>Non-GUID client_id passes through on SERA (no pre-flight rejection).</li>
 *   <li>SANDBOX routes to the sandbox host, not production.</li>
 *   <li>OAuth server unreachable → fallback token + actionable auth-failure
 *       detail (no silent success).</li>
 * </ol>
 */
class StampsSeraFlavorTest {

    /** A deliberately-not-a-GUID client_id — the shape SERA actually issues. */
    private static final String SERA_OPAQUE_CLIENT_ID = "sera-opaque-not-a-guid-abc123";

    private CarrierProperties props;
    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        // Point every URL at localhost:1 so the connector's live-call path
        // fails fast with a connect exception, exercising the fallback branch
        // WITHOUT firing real HTTP against Auctane's servers from CI.
        props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setAuthUrl("http://localhost:1/swsim");
        s.setSandboxAuthUrl("http://localhost:1/swsim");
        s.setApiBaseUrl("http://localhost:1/swsim");
        s.setSandboxUrl("http://localhost:1/swsim");
        s.setApiVersion("v135");
        // SERA URLs — the ones the fix reads from.
        s.setSeraAuthUrl("http://localhost:1/sera-prod/oauth/token");
        s.setSeraSandboxAuthUrl("http://localhost:1/sera-sandbox/oauth/token");
        s.setSeraApiBaseUrl("http://localhost:1/sera-prod/v1");
        s.setSeraSandboxApiBaseUrl("http://localhost:1/sera-sandbox/v1");
        props.setDefaultEnvironment("PRODUCTION");
        connector = new StampsConnector(props, new ObjectMapper());
    }

    // ===== flavor detection =====

    @Test
    void defaultFlavor_isSwsim_soLegacyAccountsDontRegress() {
        // A fresh CarrierProperties.Stamps() has apiFlavor="SWSIM" by field
        // initialiser; nothing in setUp overrides it. Verifying via a
        // non-GUID client_id: on SWSIM the pre-flight guard must fire and
        // set the "must be a GUID" LAST_AUTH_DETAIL. If someone flipped the
        // default to SERA this test breaks first.
        String token = connector.getAccessToken(SERA_OPAQUE_CLIENT_ID, "secret",
                "usps-account-1", "PRODUCTION");
        assertTrue(token.contains("-local-"),
                "SWSIM default with a non-GUID client_id must return a fallback token, "
                        + "not a live authenticator. Got: " + token);
        String detail = connector.consumeAuthFailureDetail();
        assertNotNull(detail, "SWSIM pre-flight rejection must set an auth-failure detail");
        assertTrue(detail.toLowerCase().contains("guid"),
                "SWSIM detail must mention 'GUID' so the operator knows the shape; got: " + detail);
    }

    @Test
    void seraFlavor_isDetectedCaseInsensitively_soPropertyOverridesAreForgiving() {
        // "sera", "SERA", "Sera" all mean SERA. Whitespace tolerated because
        // Spring's property binder trims but external config sources may not.
        for (String flavor : new String[]{"SERA", "sera", "Sera", " SERA "}) {
            props.getStamps().setApiFlavor(flavor);
            // On SERA the GUID guard is skipped, so an opaque client_id doesn't
            // trigger the "must be a GUID" LAST_AUTH_DETAIL. It will still fail
            // (localhost:1 is unreachable) but the detail must be about
            // reaching the SERA endpoint, not the GUID validator.
            connector.consumeAuthFailureDetail(); // clear anything left over
            String token = connector.getAccessToken(SERA_OPAQUE_CLIENT_ID, "secret",
                    "irrelevant", "PRODUCTION");
            assertTrue(token.contains("-local-"),
                    "flavor='" + flavor + "': SERA with unreachable host must fall back");
            String detail = connector.consumeAuthFailureDetail();
            assertNotNull(detail, "flavor='" + flavor + "': must set an auth-failure detail");
            assertFalse(detail.toLowerCase().contains("must be a guid"),
                    "flavor='" + flavor + "': SERA path must NOT surface the GUID validator "
                            + "message — that would defeat the whole fix. Got: " + detail);
        }
    }

    // ===== non-GUID client_id passes through on SERA =====

    @Test
    void seraFlavor_opaqueClientId_passesGuidValidator() {
        // The core fix: SERA operators paste an opaque string, and the
        // connector no longer rejects it pre-flight.
        props.getStamps().setApiFlavor("SERA");
        // No accountNumber — SERA doesn't require one (unlike SWSIM). This
        // must NOT throw the "needs the account number as the SWSIM Username"
        // exception the SWSIM path throws.
        String token = connector.getAccessToken(SERA_OPAQUE_CLIENT_ID, "secret",
                null, "PRODUCTION");
        assertNotNull(token, "SERA must always return a token (real or fallback), never null");
        // Local host on port 1 = connection refused, so we get a fallback.
        assertTrue(token.contains("-local-"), "unreachable SERA host must fall back");
        // Detail must be about the network / server, not the GUID validator.
        String detail = connector.consumeAuthFailureDetail();
        assertNotNull(detail);
        assertFalse(detail.toLowerCase().contains("must be a guid"),
                "opaque SERA client_id must not trigger the GUID validator; got: " + detail);
    }

    @Test
    void seraFlavor_blankClientId_stillRejectedWithoutFiringOAuth() {
        // Even on SERA, an EMPTY client_id is nonsense — we short-circuit
        // with a clear message instead of firing a doomed OAuth call.
        props.getStamps().setApiFlavor("SERA");
        String token = connector.getAccessToken("  ", "secret", null, "PRODUCTION");
        assertTrue(token.contains("-local-"), "blank SERA client_id must fall back");
        String detail = connector.consumeAuthFailureDetail();
        assertNotNull(detail);
        assertTrue(detail.toLowerCase().contains("client id"),
                "blank SERA client_id detail must mention 'Client ID' to guide the operator; got: " + detail);
    }

    // ===== SANDBOX routing =====

    @Test
    void seraFlavor_sandboxEnvironment_routesToSandboxHost() {
        // Env routing must select the sandbox token URL when environment is
        // SANDBOX so testing credentials never hit the production auth host.
        // We prove this by nulling the sandbox URL and confirming the
        // detail surfaces the "sandbox token URL not configured" message —
        // if the connector was reading the PROD URL under a SANDBOX env,
        // it would hit localhost:1 instead and show a "could not reach"
        // detail. This isolates the branch selection.
        props.getStamps().setApiFlavor("SERA");
        props.getStamps().setSeraSandboxAuthUrl(null);
        String token = connector.getAccessToken(SERA_OPAQUE_CLIENT_ID, "secret",
                null, "SANDBOX");
        assertTrue(token.contains("-local-"));
        String detail = connector.consumeAuthFailureDetail();
        assertNotNull(detail);
        assertTrue(detail.toLowerCase().contains("sandbox"),
                "with sandbox URL null and env=SANDBOX, detail must call out sandbox misconfig; got: "
                        + detail);
    }

    @Test
    void seraFlavor_productionEnvironment_routesToProductionHost() {
        // Symmetric check: nulling the PROD URL under a PROD env must yield
        // the "production token URL not configured" message. This proves the
        // branch selection is really env-driven and not accidentally
        // hardcoded on either side.
        props.getStamps().setApiFlavor("SERA");
        props.getStamps().setSeraAuthUrl(null);
        String token = connector.getAccessToken(SERA_OPAQUE_CLIENT_ID, "secret",
                null, "PRODUCTION");
        assertTrue(token.contains("-local-"));
        String detail = connector.consumeAuthFailureDetail();
        assertNotNull(detail);
        assertFalse(detail.toLowerCase().contains("sandbox"),
                "with prod URL null and env=PROD, detail must NOT reference sandbox; got: " + detail);
    }

    // ===== fallback + LAST_AUTH_DETAIL surface =====

    @Test
    void seraFlavor_serverUnreachable_surfacesActionableDetail() {
        // localhost:1 refuses; the connector must catch, set an actionable
        // detail, and return a "-local-" fallback so runCredentialCheck's
        // "-local-" gate reports the failure with the detail.
        props.getStamps().setApiFlavor("SERA");
        String token = connector.getAccessToken(SERA_OPAQUE_CLIENT_ID, "secret",
                null, "PRODUCTION");
        assertTrue(token.contains("-local-"),
                "unreachable SERA host must fall back so verify surfaces the failure");
        String detail = connector.consumeAuthFailureDetail();
        assertNotNull(detail, "unreachable SERA host must set an auth-failure detail");
        // consumeAuthFailureDetail() clears the ThreadLocal — verify.
        assertNull(connector.consumeAuthFailureDetail(),
                "auth-failure detail must be one-shot (consumed after read)");
    }
}
