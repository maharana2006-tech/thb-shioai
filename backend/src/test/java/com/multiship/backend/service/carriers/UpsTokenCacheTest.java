package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UPS U-1 regression tests for the {@link UpsConnector} OAuth token
 * cache. Pre-cache, every bulk worker fetched its own token — a
 * 500-order batch = 500 OAuth POSTs to UPS. On the sandbox
 * ({@code wwwcie.ups.com}) this routinely tripped rate limits; on
 * production it was needlessly wasteful.
 *
 * <p>Cache design decisions this test locks in:
 * <ul>
 *   <li><b>Key includes environment</b> — a sandbox token must never
 *       satisfy a production request and vice versa.</li>
 *   <li><b>Refresh margin</b> — a token expiring within
 *       {@code TOKEN_REFRESH_MARGIN_SECONDS} is treated as expired so
 *       an in-flight worker doesn't burn a token that expires
 *       mid-request.</li>
 *   <li><b>Fallback tokens are NOT cached</b> — a transient UPS OAuth
 *       outage returns a {@code ups-local-...} placeholder, and caching
 *       that would poison the cache for the whole TTL.</li>
 * </ul>
 *
 * <p>Uses reflection into the private {@code tokenCache} field to
 * pre-populate valid + expired entries and observe caching decisions
 * without needing a mock UPS OAuth server.
 */
class UpsTokenCacheTest {

    private UpsConnector connector;
    private Field tokenCacheField;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        // Deliberately point at a refused port so a cache MISS returns
        // the "ups-local-" fallback token — every test below that expects
        // a cache HIT would return the real seeded token instead.
        props.getUps().setAuthUrl("http://127.0.0.1:1/security/v1/oauth/token");
        props.getUps().setSandboxAuthUrl("http://127.0.0.1:1/security/v1/oauth/token");

        connector = new UpsConnector(props, new ObjectMapper());
        tokenCacheField = UpsConnector.class.getDeclaredField("tokenCache");
        tokenCacheField.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cache() throws Exception {
        return (Map<String, Object>) tokenCacheField.get(connector);
    }

    private static Object cachedToken(String token, Instant expiresAt) throws Exception {
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.UpsConnector$CachedToken");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(token, expiresAt);
    }

    @Test
    void cacheHitReturnsStoredTokenWithoutFetching() throws Exception {
        // Seed a valid token (expires 30 min from now — well past the 60s
        // refresh margin, so isValid() returns true).
        Object valid = cachedToken("fake-cached-ups-token",
                Instant.now().plusSeconds(1800));
        cache().put("cid-1|PRODUCTION", valid);

        String returned = connector.getAccessToken("cid-1", "secret", "acct-1", "PRODUCTION");
        assertEquals("fake-cached-ups-token", returned,
                "Cache HIT must return the stored token without calling UPS.");
    }

    @Test
    void expiredTokenBypassesCacheAndFallsBackWhenFetchFails() throws Exception {
        // Seed an already-expired token — cache lookup should skip it and
        // fall through to fetchTokenUncached, which will fail because
        // authUrl points at localhost:1 and produce a "ups-local-" fallback
        // (UpsConnector doesn't throw on OAuth failure).
        Object expired = cachedToken("stale-token", Instant.now().minusSeconds(60));
        cache().put("cid-2|PRODUCTION", expired);

        String returned = connector.getAccessToken("cid-2", "secret", "acct-2", "PRODUCTION");
        assertTrue(returned.startsWith("ups-local-"),
                "Expired token must be re-fetched; when the OAuth host is unreachable, "
                        + "we fall back to a 'ups-local-...' token. Got: " + returned);
        assertNotEquals("stale-token", returned,
                "Expired cache entry must not be returned.");
    }

    @Test
    void tokenWithinRefreshMarginIsTreatedAsExpired() throws Exception {
        // Refresh margin is 60s. A token expiring in 30s is inside the
        // margin, so isValid() must return false.
        Object marginal = cachedToken("near-expiry-token",
                Instant.now().plusSeconds(30));
        cache().put("cid-3|PRODUCTION", marginal);

        String returned = connector.getAccessToken("cid-3", "secret", "acct-3", "PRODUCTION");
        assertNotEquals("near-expiry-token", returned,
                "Token inside the refresh margin must be treated as expired.");
    }

    @Test
    void cacheKeyIsolatesEnvironments() throws Exception {
        // Same clientId, different environments must produce independent
        // cache slots — otherwise a sandbox token would satisfy a
        // production request. UPS-specific: sandbox keys ARE invalid
        // against the production OAuth host with error 10401.
        Object sandboxTok = cachedToken("SANDBOX-token", Instant.now().plusSeconds(1800));
        cache().put("cid-4|SANDBOX", sandboxTok);

        // Request PRODUCTION with the same clientId — must NOT return the
        // sandbox token. Since no PRODUCTION entry exists, it falls
        // through to fetch (fails, returns fallback).
        String prod = connector.getAccessToken("cid-4", "secret", "acct-4", "PRODUCTION");
        assertTrue(prod.startsWith("ups-local-"),
                "PRODUCTION request must NOT receive the SANDBOX-seeded token. Got: " + prod);

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
        String other = connector.getAccessToken("cid-b", "sb", "acct-b", "PRODUCTION");
        assertTrue(other.startsWith("ups-local-"),
                "Different clientId must NOT hit cid-a's cache slot. Got: " + other);
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
    void failedFetchReturnsFallbackAndDoesNotPoisonCache() throws Exception {
        // No seed → cache MISS → fetch fails against unreachable host →
        // returns fallback. Crucially, the failed fetch must NOT populate
        // the cache with a fallback token — otherwise a transient UPS
        // outage would poison the cache for the entire TTL window.
        String returned = connector.getAccessToken("cid-fail", "secret", "acct", "PRODUCTION");
        assertTrue(returned.startsWith("ups-local-"),
                "Failed fetch must fall back to a 'ups-local-...' token. Got: " + returned);
        assertNull(cache().get("cid-fail|PRODUCTION"),
                "Failed fetch must NOT populate the cache — otherwise a transient outage "
                        + "would poison the cache for the whole TTL window.");
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
        Field margin = UpsConnector.class.getDeclaredField("TOKEN_REFRESH_MARGIN_SECONDS");
        margin.setAccessible(true);
        long value = margin.getLong(null);
        assertTrue(value >= 30 && value <= 300,
                "TOKEN_REFRESH_MARGIN_SECONDS must be between 30 and 300; got " + value);
    }
}
