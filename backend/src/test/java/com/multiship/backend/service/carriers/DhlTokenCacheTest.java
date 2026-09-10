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
 * DHL verified-credentials cache regression tests. Pre-cache, every bulk
 * worker's call to {@link DhlConnector#getAccessToken(String, String, String, String)}
 * made a live GET to DHL's product-catalogue endpoint just to prove the
 * Basic Auth string works. On a 500-order bulk batch fanned out to 24
 * workers that was up to 500 verify pings against DHL per batch.
 *
 * <p>Cache design decisions this test locks in:
 * <ul>
 *   <li><b>Key includes environment</b> — a sandbox verify must never
 *       satisfy a production request and vice versa (DHL keys are
 *       environment-specific).</li>
 *   <li><b>Refresh margin</b> — an entry within
 *       {@code TOKEN_REFRESH_MARGIN_SECONDS} of expiry is treated as
 *       expired so a batch that spans the boundary doesn't have workers
 *       see mixed cached-vs-fresh states.</li>
 *   <li><b>Fallback tokens are NOT cached</b> — a transient DHL 401 or
 *       network error returns a {@code dhl-local-...} placeholder; caching
 *       it would keep the account stuck on the fallback until the TTL,
 *       including through the operator fixing the credentials.</li>
 * </ul>
 */
class DhlTokenCacheTest {

    private DhlConnector connector;
    private Field tokenCacheField;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        // Deliberately point at a refused port so a cache MISS returns
        // the "dhl-local-..." fallback token — every test below that
        // expects a cache HIT would return the real seeded token instead.
        props.getDhl().setAuthUrl("http://127.0.0.1:1/mydhlapi");
        props.getDhl().setSandboxAuthUrl("http://127.0.0.1:1/mydhlapi/test");

        connector = new DhlConnector(props, new ObjectMapper());
        tokenCacheField = DhlConnector.class.getDeclaredField("tokenCache");
        tokenCacheField.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cache() throws Exception {
        return (Map<String, Object>) tokenCacheField.get(connector);
    }

    private static Object cachedToken(String token, Instant expiresAt) throws Exception {
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.DhlConnector$CachedToken");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(token, expiresAt);
    }

    @Test
    void cacheHitReturnsStoredBasicAuthWithoutVerifyRoundTrip() throws Exception {
        // Seed a valid Basic Auth string (expires 3h from now — well past
        // the 5-min refresh margin, so isValid() returns true).
        Object valid = cachedToken("fake-cached-basic-auth",
                Instant.now().plusSeconds(10_800));
        cache().put("cid-1|PRODUCTION", valid);

        String returned = connector.getAccessToken("cid-1", "secret", "acct-1", "PRODUCTION");
        assertEquals("fake-cached-basic-auth", returned,
                "Cache HIT must return the stored Basic Auth without pinging DHL.");
    }

    @Test
    void expiredEntryBypassesCacheAndFallsBackWhenVerifyFails() throws Exception {
        // Seed an already-expired entry — cache lookup should skip it and
        // fall through to verifyAndBuildBasicAuth, which will fail
        // (localhost:1 refused) and produce a "dhl-local-..." fallback.
        Object expired = cachedToken("stale-basic", Instant.now().minusSeconds(60));
        cache().put("cid-2|PRODUCTION", expired);

        String returned = connector.getAccessToken("cid-2", "secret", "acct-2", "PRODUCTION");
        assertTrue(returned.startsWith("dhl-local-"),
                "Expired entry must be re-verified; when unreachable, we fall back to "
                        + "'dhl-local-...'. Got: " + returned);
        assertNotEquals("stale-basic", returned);
    }

    @Test
    void entryWithinRefreshMarginIsTreatedAsExpired() throws Exception {
        // Refresh margin is 300s. An entry expiring in 60s is inside the
        // margin, so isValid() must return false.
        Object marginal = cachedToken("near-expiry", Instant.now().plusSeconds(60));
        cache().put("cid-3|PRODUCTION", marginal);

        String returned = connector.getAccessToken("cid-3", "secret", "acct-3", "PRODUCTION");
        assertNotEquals("near-expiry", returned,
                "Entry inside the refresh margin must be treated as expired.");
    }

    @Test
    void cacheKeyIsolatesEnvironments() throws Exception {
        // Same clientId, different environments must produce independent
        // cache slots — DHL keys are env-specific.
        Object sandboxTok = cachedToken("SANDBOX-basic", Instant.now().plusSeconds(10_800));
        cache().put("cid-4|SANDBOX", sandboxTok);

        String prod = connector.getAccessToken("cid-4", "secret", "acct-4", "PRODUCTION");
        assertTrue(prod.startsWith("dhl-local-"),
                "PRODUCTION request must NOT receive the SANDBOX-seeded entry. Got: " + prod);

        // And SANDBOX must still hit the seeded entry.
        assertEquals("SANDBOX-basic",
                connector.getAccessToken("cid-4", "secret", "acct-4", "SANDBOX"));
    }

    @Test
    void cacheKeyIsolatesClientIds() throws Exception {
        Object t1 = cachedToken("basic-for-cid-a", Instant.now().plusSeconds(10_800));
        cache().put("cid-a|PRODUCTION", t1);

        assertEquals("basic-for-cid-a",
                connector.getAccessToken("cid-a", "sa", "acct-a", "PRODUCTION"));
        String other = connector.getAccessToken("cid-b", "sb", "acct-b", "PRODUCTION");
        assertTrue(other.startsWith("dhl-local-"),
                "Different clientId must NOT hit cid-a's cache slot. Got: " + other);
    }

    @Test
    void nullEnvironmentTreatedAsProduction() throws Exception {
        Object tok = cachedToken("prod-basic", Instant.now().plusSeconds(10_800));
        cache().put("cid-null|PRODUCTION", tok);

        assertEquals("prod-basic",
                connector.getAccessToken("cid-null", "s", "a", null));
        assertEquals("prod-basic",
                connector.getAccessToken("cid-null", "s", "a", "PRODUCTION"));
    }

    @Test
    void failedVerifyReturnsFallbackAndDoesNotPoisonCache() throws Exception {
        // No seed → cache MISS → verify fails against unreachable host →
        // returns fallback. Crucially, the failed verify must NOT populate
        // the cache with a fallback token — otherwise a transient DHL outage
        // would keep the account stuck on the fallback for the whole TTL,
        // including through the operator fixing credentials in Carriers UI.
        String returned = connector.getAccessToken("cid-fail", "secret", "acct", "PRODUCTION");
        assertTrue(returned.startsWith("dhl-local-"),
                "Failed verify must fall back to a 'dhl-local-...' token. Got: " + returned);
        assertNull(cache().get("cid-fail|PRODUCTION"),
                "Failed verify must NOT populate the cache — otherwise a transient outage "
                        + "would poison the cache for the whole TTL window.");
    }

    @Test
    void blankCredentialsShortCircuitToFallback() throws Exception {
        // Never even reach the cache when creds are blank — the pre-check
        // in getAccessToken returns immediately with a fallback token.
        String returned = connector.getAccessToken("", "", null, "PRODUCTION");
        assertTrue(returned.startsWith("dhl-local-"),
                "Blank credentials must short-circuit to fallback. Got: " + returned);
        assertTrue(cache().isEmpty(),
                "Blank credentials must NOT populate the cache.");
    }

    @Test
    void clearTokenCacheDrainsAllEntries() throws Exception {
        cache().put("cid-x|PRODUCTION",
                cachedToken("x-basic", Instant.now().plusSeconds(10_800)));
        cache().put("cid-y|SANDBOX",
                cachedToken("y-basic", Instant.now().plusSeconds(10_800)));
        assertEquals(2, cache().size());

        connector.clearTokenCache();
        assertTrue(cache().isEmpty(), "clearTokenCache() must empty the cache.");
        assertNull(cache().get("cid-x|PRODUCTION"));
    }

    @Test
    void ttlAndRefreshMarginConstantsAreSane() throws Exception {
        Field ttl = DhlConnector.class.getDeclaredField("TOKEN_TTL_MINUTES");
        ttl.setAccessible(true);
        long ttlMin = ttl.getLong(null);
        assertTrue(ttlMin >= 15 && ttlMin <= 1440,
                "TOKEN_TTL_MINUTES must be between 15 and 1440 (24h); got " + ttlMin);

        Field margin = DhlConnector.class.getDeclaredField("TOKEN_REFRESH_MARGIN_SECONDS");
        margin.setAccessible(true);
        long marginSec = margin.getLong(null);
        assertTrue(marginSec >= 60 && marginSec <= ttlMin * 60 / 2,
                "TOKEN_REFRESH_MARGIN_SECONDS must be >=60s and <=half the TTL; got " + marginSec);
    }
}
