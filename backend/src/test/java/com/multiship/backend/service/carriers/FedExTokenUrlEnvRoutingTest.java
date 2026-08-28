package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F-MODE-1 regression tests — {@link FedExConnector#getAccessToken} now
 * routes the OAuth token URL by the caller's {@code environment}
 * argument rather than reading the global default. Pre-fix, any FedEx
 * account whose environment didn't match {@code carrier.default-environment}
 * silently 401'd against the wrong host (FedEx sandbox and prod credentials
 * are not interchangeable), and verify reported "credentials rejected"
 * for CORRECT keys.
 *
 * <p>Tests use reflection into the private {@code getTokenUrl(String)}
 * because a full HTTP round-trip would require a mock server; the URL
 * selection is the load-bearing logic here.
 */
class FedExTokenUrlEnvRoutingTest {

    private FedExConnector connector;
    private Method getTokenUrl;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        // Configure realistic FedEx URLs to match application.properties.
        props.getFedEx().setApiBaseUrl("https://apis.fedex.com");
        props.getFedEx().setSandboxUrl("https://apis-sandbox.fedex.com");
        props.getFedEx().setAuthUrl("https://apis.fedex.com/oauth/token");
        props.getFedEx().setTokenPath("/oauth/token");
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        // Pin the GLOBAL default env so we can prove the router IGNORES it
        // and consults the caller-supplied env instead.
        props.setDefaultEnvironment("PRODUCTION");

        connector = new FedExConnector(props, new ObjectMapper(), noFx());
        getTokenUrl = FedExConnector.class.getDeclaredMethod("getTokenUrl", String.class);
        getTokenUrl.setAccessible(true);
    }

    private String tokenUrl(String environment) throws Exception {
        return (String) getTokenUrl.invoke(connector, environment);
    }

    private static com.multiship.backend.service.fx.FxRateService noFx() {
        return new com.multiship.backend.service.fx.FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    @Test
    void sandboxEnvRoutesToSandboxTokenUrl() throws Exception {
        // F-MODE-1 — SANDBOX-passed accounts route to the sandbox OAuth host
        // regardless of what carrier.default-environment says globally.
        assertEquals("https://apis-sandbox.fedex.com/oauth/token", tokenUrl("SANDBOX"));
    }

    @Test
    void sandboxCaseInsensitive() throws Exception {
        // The env string can arrive lower-cased from stored CarrierConfig
        // rows on legacy tenants; guard against equalsIgnoreCase drift.
        assertEquals("https://apis-sandbox.fedex.com/oauth/token", tokenUrl("sandbox"));
        assertEquals("https://apis-sandbox.fedex.com/oauth/token", tokenUrl("Sandbox"));
    }

    @Test
    void productionEnvRoutesToConfiguredAuthUrl() throws Exception {
        // PRODUCTION uses the configured authUrl (hardcoded to the prod host).
        assertEquals("https://apis.fedex.com/oauth/token", tokenUrl("PRODUCTION"));
    }

    @Test
    void nullEnvRoutesToProductionByDefault() throws Exception {
        // F-MODE-1 pre-fix behaviour was: null env → read global default →
        // could route to sandbox. Post-fix: null env conservatively routes
        // to production (the authUrl) so callers who haven't been upgraded
        // to the 4-arg overload never accidentally hit sandbox with prod
        // credentials. UPS follows the same "null → production" convention.
        assertEquals("https://apis.fedex.com/oauth/token", tokenUrl(null));
    }

    @Test
    void unknownEnvRoutesToProduction() throws Exception {
        // "STAGING" or any non-SANDBOX value routes to production, matching
        // the classic environment-tolerant string check across the codebase.
        assertEquals("https://apis.fedex.com/oauth/token", tokenUrl("STAGING"));
        assertEquals("https://apis.fedex.com/oauth/token", tokenUrl(""));
    }

    @Test
    void globalDefaultEnvironmentIsIgnored() throws Exception {
        // The core F-MODE-1 assertion: even with the global default set to
        // SANDBOX, a caller passing PRODUCTION reaches the production URL.
        // Pre-fix this would have routed to sandbox because getTokenUrl()
        // read carrierProperties.getDefaultEnvironment() directly.
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setApiBaseUrl("https://apis.fedex.com");
        props.getFedEx().setSandboxUrl("https://apis-sandbox.fedex.com");
        props.getFedEx().setAuthUrl("https://apis.fedex.com/oauth/token");
        props.getFedEx().setTokenPath("/oauth/token");
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        props.setDefaultEnvironment("SANDBOX");   // global default flipped

        FedExConnector isolated = new FedExConnector(props, new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("getTokenUrl", String.class);
        m.setAccessible(true);
        assertEquals("https://apis.fedex.com/oauth/token", m.invoke(isolated, "PRODUCTION"));
    }
}
