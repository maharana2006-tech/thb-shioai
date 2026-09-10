package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link FedExConnector} per-account OAuth token cache
 * introduced to stop the 24-worker bulk fan-out from making 500+
 * OAuth POSTs per batch. Pre-cache, every {@code createShipment}
 * fetched its own token; on FedEx sandbox this routinely tripped rate
 * limits and, on prod, hammered the OAuth host for no reason.
 *
 * <p>Uses reflection into the private {@code tokenCache} field to
 * pre-populate valid + expired entries and observe caching decisions
 * without needing a mock FedEx OAuth server.
 */
class FedExTokenCacheTest {

    private FedExConnector connector;
    private Field tokenCacheField;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setApiBaseUrl("https://apis.fedex.com");
        props.getFedEx().setSandboxUrl("https://apis-sandbox.fedex.com");
        // Deliberately point at a refused port so a cache MISS would throw
        // immediately — every test below that expects a cache HIT would fail
        // loudly if the cache logic were skipped.
        props.getFedEx().setAuthUrl("http://127.0.0.1:1/oauth/token");
        props.getFedEx().setTokenPath("/oauth/token");
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        props.setDefaultEnvironment("PRODUCTION");

        connector = new FedExConnector(props, new ObjectMapper(), noFx());
        tokenCacheField = FedExConnector.class.getDeclaredField("tokenCache");
        tokenCacheField.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cache() throws Exception {
        return (Map<String, Object>) tokenCacheField.get(connector);
    }

    /** Reflectively construct a CachedToken record (private) with an arbitrary expiry. */
    private static Object cachedToken(String token, Instant expiresAt) throws Exception {
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.FedExConnector$CachedToken");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(token, expiresAt);
    }

    @Test
    void cacheHitReturnsStoredTokenWithoutFetching() throws Exception {
        // Seed a valid token (expires 30 min from now — well past the 60s
        // refresh margin, so isValid() returns true).
        Object valid = cachedToken("fake-cached-access-token",
                Instant.now().plusSeconds(1800));
        cache().put("cid-1|PRODUCTION", valid);

        String returned = connector.getAccessToken("cid-1", "secret", "acct-1", "PRODUCTION");
        assertEquals("fake-cached-access-token", returned,
                "Cache HIT must return the stored token without calling FedEx.");
    }

    @Test
    void expiredTokenBypassesCacheAndTriesToFetch() throws Exception {
        // Seed an already-expired token — cache lookup should skip it and
        // fall through to fetchTokenUncached, which will fail because
        // authUrl points at localhost:1. That failure proves the expired
        // entry did NOT satisfy the request.
        Object expired = cachedToken("stale-token", Instant.now().minusSeconds(60));
        cache().put("cid-2|PRODUCTION", expired);

        assertThrows(com.multiship.backend.exception.CarrierConnectionException.class,
                () -> connector.getAccessToken("cid-2", "secret", "acct-2", "PRODUCTION"),
                "Expired token must be re-fetched (which fails when the OAuth host is unreachable).");
    }

    @Test
    void tokenWithinRefreshMarginIsTreatedAsExpired() throws Exception {
        // Refresh margin is 60s. A token expiring in 30s is inside the
        // margin, so isValid() must return false and the connector must
        // try to refresh (and fail against the unreachable stub).
        Object marginal = cachedToken("near-expiry-token",
                Instant.now().plusSeconds(30));
        cache().put("cid-3|PRODUCTION", marginal);

        assertThrows(com.multiship.backend.exception.CarrierConnectionException.class,
                () -> connector.getAccessToken("cid-3", "secret", "acct-3", "PRODUCTION"),
                "Token inside the refresh margin must be treated as expired.");
    }

    @Test
    void cacheKeyIsolatesEnvironments() throws Exception {
        // Same clientId, different environments must produce independent
        // cache slots — otherwise a sandbox token would satisfy a
        // production request (and vice versa).
        Object sandboxTok = cachedToken("SANDBOX-token", Instant.now().plusSeconds(1800));
        cache().put("cid-4|SANDBOX", sandboxTok);

        // Request PRODUCTION with the same clientId — must NOT return the
        // sandbox token. Since no PRODUCTION entry exists, it falls through
        // to fetch (which fails against the unreachable stub).
        assertThrows(com.multiship.backend.exception.CarrierConnectionException.class,
                () -> connector.getAccessToken("cid-4", "secret", "acct-4", "PRODUCTION"),
                "Environments must have separate cache slots.");

        // And SANDBOX must still hit the seeded entry.
        assertEquals("SANDBOX-token",
                connector.getAccessToken("cid-4", "secret", "acct-4", "SANDBOX"));
    }

    @Test
    void cacheKeyIsolatesClientIds() throws Exception {
        // Two accounts using different clientIds must not share a cache slot.
        Object t1 = cachedToken("token-for-cid-a", Instant.now().plusSeconds(1800));
        cache().put("cid-a|PRODUCTION", t1);

        assertEquals("token-for-cid-a",
                connector.getAccessToken("cid-a", "sa", "acct-a", "PRODUCTION"));
        assertThrows(com.multiship.backend.exception.CarrierConnectionException.class,
                () -> connector.getAccessToken("cid-b", "sb", "acct-b", "PRODUCTION"));
    }

    @Test
    void nullEnvironmentTreatedAsProduction() throws Exception {
        // Callers on the legacy 2/3-arg overloads pass null environment.
        // The cache key normalises those to "PRODUCTION" so a subsequent
        // 4-arg call with environment="PRODUCTION" hits the same slot.
        Object tok = cachedToken("prod-token", Instant.now().plusSeconds(1800));
        cache().put("cid-null|PRODUCTION", tok);

        assertEquals("prod-token",
                connector.getAccessToken("cid-null", "s", "a", null));
        assertEquals("prod-token",
                connector.getAccessToken("cid-null", "s", "a", "PRODUCTION"));
    }

    @Test
    void clearTokenCacheDrainsAllEntries() throws Exception {
        cache().put("cid-x|PRODUCTION",
                cachedToken("x-tok", Instant.now().plusSeconds(1800)));
        cache().put("cid-y|SANDBOX",
                cachedToken("y-tok", Instant.now().plusSeconds(1800)));
        assertEquals(2, cache().size());

        connector.clearTokenCache();
        assertTrue(cache().isEmpty(), "clearTokenCache() must empty the cache.");
        assertNull(cache().get("cid-x|PRODUCTION"));
    }

    @Test
    void nearExpiryConstantIsSaneNotZero() throws Exception {
        // Guard against a future edit that lowers the refresh margin to 0
        // (which would let workers use tokens that expire mid-request).
        Field margin = FedExConnector.class.getDeclaredField("TOKEN_REFRESH_MARGIN_SECONDS");
        margin.setAccessible(true);
        long value = margin.getLong(null);
        assertTrue(value >= 30 && value <= 300,
                "TOKEN_REFRESH_MARGIN_SECONDS must be between 30 and 300; got " + value);
    }

    private static com.multiship.backend.service.fx.FxRateService noFx() {
        return new com.multiship.backend.service.fx.FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    // Silence unused-imports if the assertNotNull import is ever removed.
    static { assertNotNull(""); }
}
