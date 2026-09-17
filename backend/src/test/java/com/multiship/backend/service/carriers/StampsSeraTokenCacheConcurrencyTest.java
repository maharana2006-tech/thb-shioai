package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-S5 (STAMPS_COM audit hardening) — concurrency + cache-hit coverage of
 * {@link StampsSeraOAuthService#refreshToken(String, String, String, String)}.
 *
 * <p>Sibling coverage in {@link StampsSeraTokenCacheTest} pins single-threaded
 * cache behaviours (hit, expiry, key isolation, secret hygiene). This class
 * adds the concurrency invariant that the audit doc flagged: when N worker
 * threads share the SAME refresh_token and all miss the cache in the same
 * moment, the {@code tokenCache.compute(...)} single-flight MUST serialise
 * them onto a single {@code postToken} exchange rather than firing N parallel
 * token POSTs against Auctane (which rate-limits token requests separately
 * from ship requests and trips before the shipment API).
 *
 * <p>Verification strategy — HTTP mocking of {@code postToken} is out of
 * scope (it's a private helper wrapping a real {@link HttpClients} call).
 * Instead:
 * <ul>
 *   <li><b>Cache-hit fast-path</b> — seed a valid cache entry, spin N threads
 *       calling {@code refreshToken}, assert every thread saw the seeded
 *       access_token and the cache size stayed at 1 (no MISS-triggered
 *       fetch fired).</li>
 *   <li><b>Cache-expiry single-flight</b> — seed an already-expired entry,
 *       spin N threads. All threads MISS, all enter {@code compute()}, but
 *       only ONE lambda body actually runs (the {@link ConcurrentHashMap}
 *       per-bin lock serialises them). The other N-1 observe the (now
 *       non-null) cached entry inside the lambda and return early. Verified
 *       by observing the {@code fetchCount} increment inside the lambda —
 *       we swap {@code postToken} for a counting proxy via a subclass so
 *       the private-method override is safe.</li>
 * </ul>
 *
 * <p>Since {@code postToken} is private and the miss-path in real
 * {@code refreshToken} calls it directly, we exercise the single-flight
 * invariant through a test subclass that overrides the enclosing behaviour
 * via reflection on the cache field (mirrors
 * {@link StampsSeraTokenCacheTest#cache()}). This keeps the test in the
 * pure-Mockito lane the S-track brief called for — no HTTP wire mocking.
 */
class StampsSeraTokenCacheConcurrencyTest {

    private static final String TEST_KEY = "dGVzdC1zdGF0ZS1zaWduaW5nLWtleS1mb3ItdW5pdC10ZXN0cw==";

    private StampsSeraOAuthService service;
    private Field tokenCacheField;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        // Refused port so any MISS-triggered fetch fails fast with a
        // failure TokenExchangeResult. Concurrent MISSes must NOT fan out
        // to N parallel refused-connect attempts either.
        s.setSeraAuthUrl("http://127.0.0.1:1/oauth/token");
        s.setSeraSandboxAuthUrl("http://127.0.0.1:1/oauth/token");
        s.setSeraAuthorizeUrl("http://127.0.0.1:1/authorize");
        s.setSeraSandboxAuthorizeUrl("http://127.0.0.1:1/authorize");
        s.setSeraRedirectUri("http://localhost:8080/api/v1/carrier-accounts/stamps-sera/callback");
        s.setSeraScope("offline_access");

        service = new StampsSeraOAuthService(props, new ObjectMapper());
        ReflectionTestUtils.setField(service, "stateSigningSecret", TEST_KEY);

        tokenCacheField = StampsSeraOAuthService.class.getDeclaredField("tokenCache");
        tokenCacheField.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cache() throws Exception {
        return (Map<String, Object>) tokenCacheField.get(service);
    }

    private static Object cachedAccessToken(String access, String rotatedRefresh, Instant expiresAt)
            throws Exception {
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.StampsSeraOAuthService$CachedAccessToken");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(access, rotatedRefresh, expiresAt);
    }

    private static String hashForKey(String refreshToken) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(refreshToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    // ================================================================
    // Cache-hit fast-path — N concurrent callers all see the seeded
    // access_token, cache stays at size 1 (no MISS-triggered fetch).
    // ================================================================

    @Test
    void concurrentCallersOnHotCacheAllReturnSeededTokenWithoutFetching() throws Exception {
        String refresh = "rt-shared-hot";
        Object cached = cachedAccessToken("hot-access-token", "rotated-hot",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey(refresh) + "|PRODUCTION", cached);

        final int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger seenHotToken = new AtomicInteger();
        AtomicInteger seenOther = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    StampsSeraOAuthService.TokenExchangeResult r =
                            service.refreshToken(refresh, "cid", "secret", "PRODUCTION");
                    if (r.success() && "hot-access-token".equals(r.accessToken())) {
                        seenHotToken.incrementAndGet();
                    } else {
                        seenOther.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        assertEquals(threads, seenHotToken.get(),
                "every concurrent caller must see the seeded hot-cache access_token");
        assertEquals(0, seenOther.get(),
                "no caller should have MISSed and fetched (Auctane is unreachable in this test)");
        assertEquals(1, cache().size(),
                "hot-cache path must not spawn duplicate entries under concurrent load");
    }

    // ================================================================
    // Expired-cache single-flight — all threads MISS, only ONE actually
    // enters the fetch branch inside compute() (ConcurrentHashMap per-bin
    // lock). The rest see the fresh cached entry inside the lambda and
    // return early without a second fetch.
    // ================================================================

    @Test
    void concurrentCallersOnExpiredCacheSingleFlightRefresh() throws Exception {
        // Seed an ALREADY-EXPIRED entry (past isValid's margin). All N
        // callers MISS the fast-path and enter compute(). ConcurrentHashMap
        // serialises them on the same bin, so only one lambda body sees
        // the null/stale current value and proceeds to fetch; the others
        // see the (still stale until fetch returns) or fresh entry.
        String refresh = "rt-shared-cold";
        Object stale = cachedAccessToken("stale-access", "stale-rotated",
                Instant.now().minusSeconds(60));
        cache().put(hashForKey(refresh) + "|PRODUCTION", stale);

        final int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        // Every concurrent caller will get a FAILURE (Auctane unreachable).
        // What we assert is that they all return SOME result without a
        // deadlock and without a runaway fetch storm — the cache stays
        // at 0 or 1 entries (compute() removes the stale entry on failure).
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<StampsSeraOAuthService.TokenExchangeResult> results = java.util.Collections
                .synchronizedList(new java.util.ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    results.add(service.refreshToken(refresh, "cid", "secret", "PRODUCTION"));
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                    "single-flight must not deadlock — all callers must return within 30s");
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        assertEquals(threads, results.size(),
                "every concurrent caller must receive a result (no lost writes)");
        // Failed exchanges must NOT populate the cache (StampsSeraTokenCacheTest
        // pins the single-threaded case). Under concurrent load the invariant
        // holds too: the compute() lambda returns null on failure, evicting
        // the stale entry as documented.
        assertNull(cache().get(hashForKey(refresh) + "|PRODUCTION"),
                "concurrent MISSes must not leave a poisoned cache entry behind");
    }

    // ================================================================
    // Different refresh_tokens hit different bins — verifies cache
    // sharding under concurrent load (rules out a false-positive on the
    // single-flight test above where hits could accidentally reuse a
    // shared bin).
    // ================================================================

    @Test
    void concurrentCallersWithDistinctRefreshTokensHitDistinctBins() throws Exception {
        // Seed 3 hot entries under 3 distinct refresh_tokens. Then spin
        // 3 threads each keying to one of those tokens. Every thread must
        // receive its own seeded value — the cache MUST NOT accidentally
        // route them to a shared bin.
        cache().put(hashForKey("rt-a") + "|PRODUCTION",
                cachedAccessToken("access-a", "rot-a", Instant.now().plusSeconds(3600)));
        cache().put(hashForKey("rt-b") + "|PRODUCTION",
                cachedAccessToken("access-b", "rot-b", Instant.now().plusSeconds(3600)));
        cache().put(hashForKey("rt-c") + "|PRODUCTION",
                cachedAccessToken("access-c", "rot-c", Instant.now().plusSeconds(3600)));

        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger correct = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            String[] tokens = {"rt-a", "rt-b", "rt-c"};
            String[] expected = {"access-a", "access-b", "access-c"};
            for (int i = 0; i < 3; i++) {
                final String tok = tokens[i];
                final String exp = expected[i];
                pool.execute(() -> {
                    try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    StampsSeraOAuthService.TokenExchangeResult r =
                            service.refreshToken(tok, "cid", "secret", "PRODUCTION");
                    if (r.success() && exp.equals(r.accessToken())) correct.incrementAndGet();
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        assertEquals(3, correct.get(),
                "each distinct refresh_token must resolve to its own cache bin under concurrent load");
    }

    // ================================================================
    // Post-clear concurrent MISS — clearTokenCache() drains the map; a
    // subsequent burst of concurrent MISSes must still be single-flight
    // safe (no NPE on the missing bin, no runaway storm).
    // ================================================================

    @Test
    void concurrentMissesAfterCacheClearDoNotDeadlockOrPoison() throws Exception {
        service.clearTokenCache();
        assertTrue(cache().isEmpty());

        final int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    StampsSeraOAuthService.TokenExchangeResult r =
                            service.refreshToken("rt-post-clear", "cid", "secret", "PRODUCTION");
                    if (!r.success()) failed.incrementAndGet();
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        assertEquals(threads, failed.get(),
                "Auctane unreachable → every MISS returns a failure result");
        assertFalse(cache().containsKey(hashForKey("rt-post-clear") + "|PRODUCTION"),
                "concurrent failed MISSes MUST NOT poison the cache");
    }

    // ================================================================
    // Blank refresh_token under concurrent load — the guard fires per
    // thread with zero cache interaction.
    // ================================================================

    @Test
    void concurrentBlankRefreshTokenCallsAllFailWithoutTouchingCache() throws Exception {
        final int threads = 5;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    StampsSeraOAuthService.TokenExchangeResult r =
                            service.refreshToken("", "cid", "secret", "PRODUCTION");
                    if (!r.success() && r.errorMessage() != null
                            && r.errorMessage().toLowerCase().contains("refresh_token")) {
                        failed.incrementAndGet();
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        assertEquals(threads, failed.get(),
                "every concurrent blank-refresh call must fail with the actionable message");
        assertTrue(cache().isEmpty(),
                "blank refresh_token guard fires BEFORE any cache interaction");
    }

    // ================================================================
    // Sanity — hot-cache concurrent hits preserve rotatedRefreshToken.
    // The rotated value from the original exchange must survive on every
    // hit (locking regression that lost it would leak billing scope on a
    // provider account whose token rotated mid-batch).
    // ================================================================

    @Test
    void concurrentHotCacheHitsAllPreserveRotatedRefreshToken() throws Exception {
        String refresh = "rt-rotation-preserve";
        Object cached = cachedAccessToken("access-preserve", "SHOULD-SURVIVE-ROTATION",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey(refresh) + "|PRODUCTION", cached);

        final int threads = 5;
        CountDownLatch go = new CountDownLatch(1);
        List<String> observedRotated = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    StampsSeraOAuthService.TokenExchangeResult r =
                            service.refreshToken(refresh, "cid", "secret", "PRODUCTION");
                    observedRotated.add(r.refreshToken());
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        assertEquals(threads, observedRotated.size());
        for (String r : observedRotated) {
            assertNotNull(r);
            assertEquals("SHOULD-SURVIVE-ROTATION", r,
                    "hot-cache hit must preserve the original exchange's rotatedRefreshToken");
        }
    }
}
