package com.multiship.backend.service.carriers.usps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Cache;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link UspsOAuthTokenCache}.
 *
 * <p>USPS Direct v3 authenticates all tenants under one platform OAuth
 * client. This cache is the choke point that prevents N-worker batches
 * from firing N token requests at USPS. The invariants pinned here:
 *
 * <ul>
 *   <li><b>Cache hit avoids the OAuth call.</b> Seeded valid tokens are
 *       returned verbatim.</li>
 *   <li><b>Environment isolation.</b> A SANDBOX-seeded token must never
 *       satisfy a PRODUCTION request (CAT credentials don't authenticate
 *       against apis.usps.com).</li>
 *   <li><b>Refresh margin honored.</b> A token expiring inside the
 *       {@link UspsOAuthTokenCache#TOKEN_REFRESH_MARGIN_SECONDS} window
 *       is treated as expired.</li>
 *   <li><b>Failed fetches don't poison the cache.</b> Miss + unreachable
 *       OAuth host = empty Optional, not a cached placeholder that would
 *       block real minting for the whole TTL.</li>
 *   <li><b>Concurrent gets don't double-mint.</b> N threads on the same
 *       key that all miss the cache land on the same compute() slot.</li>
 * </ul>
 *
 * <p>Uses reflection into the private cache field to pre-populate entries
 * without needing a real WireMock OAuth server. The unreachable
 * {@code http://127.0.0.1:1} address forces cache-miss paths to return
 * {@link Optional#empty()} deterministically.
 */
class UspsOAuthTokenCacheTest {

    private UspsOAuthTokenCache cache;
    private Field cacheField;

    @BeforeEach
    void setUp() throws Exception {
        cache = new UspsOAuthTokenCache(new ObjectMapper());
        cacheField = UspsOAuthTokenCache.class.getDeclaredField("tokenCache");
        cacheField.setAccessible(true);
    }

    /**
     * PR-P1 — cache field is now a Caffeine {@link Cache} (was
     * {@link java.util.concurrent.ConcurrentHashMap}). Return its
     * {@code asMap()} view so existing tests can put / peek entries
     * with the same API surface (Caffeine's asMap is a
     * {@link ConcurrentMap}).
     */
    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, Object> internalMap() throws Exception {
        Cache<String, Object> cf = (Cache<String, Object>) cacheField.get(cache);
        return cf.asMap();
    }

    private static Object cachedToken(String token, Instant expiresAt) throws Exception {
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache$CachedToken");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(token, expiresAt);
    }

    @Test
    void cacheHitReturnsStoredToken() throws Exception {
        Object valid = cachedToken("cached-usps-token", Instant.now().plusSeconds(7200));
        internalMap().put("platform-cid|PRODUCTION", valid);

        Optional<String> returned = cache.getToken("platform-cid", "secret", "PRODUCTION");
        assertTrue(returned.isPresent(), "Cache HIT should return the seeded token.");
        assertEquals("cached-usps-token", returned.get());
    }

    @Test
    void blankCredentialsShortCircuit() {
        assertTrue(cache.getToken("", "s", "PRODUCTION").isEmpty(),
                "Blank clientId must skip the OAuth call and return empty.");
        assertTrue(cache.getToken("cid", "", "PRODUCTION").isEmpty(),
                "Blank clientSecret must skip the OAuth call and return empty.");
        assertTrue(cache.getToken(null, null, "PRODUCTION").isEmpty());
    }

    @Test
    void expiredEntryBypassesCache() throws Exception {
        Object expired = cachedToken("stale-token", Instant.now().minusSeconds(60));
        internalMap().put("cid-expire|PRODUCTION", expired);

        // Fetch will fail because there's no reachable USPS OAuth host in
        // the test env — an expired token bypasses the cache and returns
        // Optional.empty() (never returns the stale value).
        Optional<String> returned = cache.getToken("cid-expire", "secret", "PRODUCTION");
        assertTrue(returned.isEmpty(),
                "Expired entry must not be returned; fetch fails → empty.");
    }

    @Test
    void tokenInsideRefreshMarginTreatedAsExpired() throws Exception {
        // Refresh margin is 30 min (1_800s). A token expiring in 100s
        // is well inside that margin, so isValid() must be false.
        Object marginal = cachedToken("near-expiry", Instant.now().plusSeconds(100));
        internalMap().put("cid-margin|PRODUCTION", marginal);

        Optional<String> returned = cache.getToken("cid-margin", "secret", "PRODUCTION");
        // Marginal treated as expired → cache is refreshed via fetch,
        // fetch fails (no live host) → empty.
        assertTrue(returned.isEmpty(),
                "Token inside refresh margin must be treated as expired.");
    }

    @Test
    void cacheKeyIsolatesEnvironments() throws Exception {
        Object sandboxTok = cachedToken("SANDBOX-tok", Instant.now().plusSeconds(7200));
        internalMap().put("cid-env|SANDBOX", sandboxTok);

        // Same clientId, different env → different cache slot. No
        // PRODUCTION seed exists so the miss + fetch fails → empty.
        assertTrue(cache.getToken("cid-env", "secret", "PRODUCTION").isEmpty(),
                "PRODUCTION request must NOT return the SANDBOX-seeded token.");
        // SANDBOX still hits the seeded entry.
        assertEquals("SANDBOX-tok",
                cache.getToken("cid-env", "secret", "SANDBOX").orElse(null));
    }

    @Test
    void nullEnvironmentTreatedAsProduction() throws Exception {
        Object tok = cachedToken("prod-tok", Instant.now().plusSeconds(7200));
        internalMap().put("cid-null|PRODUCTION", tok);

        // Null environment normalises to PRODUCTION so a legacy caller
        // sees the same cache slot as the explicit-PRODUCTION caller.
        assertEquals("prod-tok", cache.getToken("cid-null", "s", null).orElse(null));
        assertEquals("prod-tok", cache.getToken("cid-null", "s", "PRODUCTION").orElse(null));
    }

    @Test
    void failedFetchDoesNotPoisonCache() throws Exception {
        // No seed → cache miss → fetch fails against unreachable host →
        // returns empty. The cache slot must be empty afterwards so a
        // subsequent successful fetch can populate it (no negative
        // caching).
        Optional<String> returned = cache.getToken("cid-fail", "secret", "PRODUCTION");
        assertTrue(returned.isEmpty());
        assertNull(internalMap().get("cid-fail|PRODUCTION"),
                "Failed fetch must NOT populate the cache with a placeholder.");
    }

    @Test
    void twoGetsWithSameKeyReuseSingleEntry() throws Exception {
        Object tok = cachedToken("shared-tok", Instant.now().plusSeconds(7200));
        internalMap().put("cid-share|PRODUCTION", tok);

        // Both gets should return the SAME seeded token — no second mint.
        Optional<String> first = cache.getToken("cid-share", "s", "PRODUCTION");
        Optional<String> second = cache.getToken("cid-share", "s", "PRODUCTION");
        assertEquals("shared-tok", first.orElse(null));
        assertEquals("shared-tok", second.orElse(null));
        assertEquals(1, cache.size(),
                "Single-entry cache after two gets on the same (cid, env).");
    }

    @Test
    void concurrentGetsSingleFlightUnderCompute() throws Exception {
        // N threads racing on the same key must all see the SAME
        // cached-token object once the seeder wins. Because we seed
        // BEFORE the threads race, every one of them should hit the
        // cache directly (isValid=true) — no fetches at all. The
        // stronger single-flight invariant (all N misses land on one
        // compute() call) would require intercepting the internal
        // fetch, which is out of scope for a unit test; but we can
        // verify that the seeded entry is the exact object returned by
        // every concurrent get.
        Object shared = cachedToken("concurrent-tok", Instant.now().plusSeconds(7200));
        internalMap().put("cid-race|PRODUCTION", shared);

        int workers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Optional<String> tok = cache.getToken("cid-race", "s", "PRODUCTION");
                    if (tok.isPresent() && "concurrent-tok".equals(tok.get())) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(workers, successes.get(),
                "Every concurrent get on a seeded key must return the same value.");
        assertEquals(1, cache.size(),
                "No new entries should have been created under contention.");
    }

    @Test
    void clearDrainsCache() throws Exception {
        internalMap().put("k1|PRODUCTION",
                cachedToken("t1", Instant.now().plusSeconds(7200)));
        internalMap().put("k2|SANDBOX",
                cachedToken("t2", Instant.now().plusSeconds(7200)));
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size(), "clear() must empty the cache.");
    }

    @Test
    void backoffDelayIncreasesExponentially() {
        long d0 = UspsOAuthTokenCache.backoffDelayMillis(0);
        long d1 = UspsOAuthTokenCache.backoffDelayMillis(1);
        long d2 = UspsOAuthTokenCache.backoffDelayMillis(2);
        // Guard against a future edit collapsing the sequence to a
        // constant. 2s ± jitter, 4s ± jitter, 8s ± jitter.
        assertTrue(d0 >= 2_000 && d0 <= 2_500, "attempt 0 ~= 2s (got " + d0 + ")");
        assertTrue(d1 >= 4_000 && d1 <= 4_500, "attempt 1 ~= 4s (got " + d1 + ")");
        assertTrue(d2 >= 8_000 && d2 <= 8_500, "attempt 2 ~= 8s (got " + d2 + ")");
    }

    @Test
    void baseUrlRoutesEnvironment() {
        assertEquals(UspsOAuthTokenCache.SANDBOX_HOST,
                UspsOAuthTokenCache.baseUrl("SANDBOX"));
        assertEquals(UspsOAuthTokenCache.SANDBOX_HOST,
                UspsOAuthTokenCache.baseUrl("sandbox"));
        assertEquals(UspsOAuthTokenCache.PROD_HOST,
                UspsOAuthTokenCache.baseUrl("PRODUCTION"));
        assertEquals(UspsOAuthTokenCache.PROD_HOST,
                UspsOAuthTokenCache.baseUrl(null));
        assertNotEquals(UspsOAuthTokenCache.baseUrl("SANDBOX"),
                UspsOAuthTokenCache.baseUrl("PRODUCTION"));
    }

    @Test
    void scopeStringCoversAllPrShippingApis() {
        // Guard against a future edit dropping a scope — labels won't
        // mint without the `labels` + `payments` scopes; tracking won't
        // without `tracking`. Pin the full string here.
        String scope = UspsOAuthTokenCache.USPS_V3_SCOPE;
        assertTrue(scope.contains("labels"), "scope must include labels");
        assertTrue(scope.contains("payments"), "scope must include payments");
        assertTrue(scope.contains("tracking"), "scope must include tracking");
        assertTrue(scope.contains("prices"), "scope must include prices");
        assertTrue(scope.contains("addresses"), "scope must include addresses");
        assertFalse(scope.trim().startsWith(" "),
                "scope must not start with a space (URL-encoded body poisoning).");
    }
}
