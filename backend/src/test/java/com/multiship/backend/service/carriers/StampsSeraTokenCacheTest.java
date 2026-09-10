package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the SERA access-token cache. Pre-cache, every
 * carrier call for a Stamps.com SERA-flavored account POSTed the
 * refresh_token to Auctane's OAuth endpoint to mint a fresh
 * access_token. On a 500-order bulk batch with the 24-worker pool that
 * was up to 500 refresh_token exchanges per batch — Auctane rate-limits
 * token requests separately from ship requests, so this saturated first.
 *
 * <p>Cache design decisions this test locks in:
 * <ul>
 *   <li><b>Key includes environment</b> — sandbox refresh_tokens must
 *       never satisfy a production request.</li>
 *   <li><b>Key uses SHA-256 of the refresh_token, not the token itself</b>
 *       — refresh_tokens are secrets we don't hold as map keys.</li>
 *   <li><b>Cached TTL comes from response's expires_in</b> — SERA
 *       controls the lifetime; we honour it minus a 60s margin.</li>
 *   <li><b>Failures are NEVER cached</b> — a transient Auctane outage
 *       must not poison the cache for the whole TTL.</li>
 *   <li><b>refresh_token rotation invalidates the cache lazily</b> — a
 *       new refresh_token hashes to a different key, so we miss the
 *       cache once and re-fetch (correct).</li>
 * </ul>
 */
class StampsSeraTokenCacheTest {

    private static final String TEST_KEY = "dGVzdC1zdGF0ZS1zaWduaW5nLWtleS1mb3ItdW5pdC10ZXN0cw==";

    private StampsSeraOAuthService service;
    private CarrierProperties props;
    private Field tokenCacheField;

    @BeforeEach
    void setUp() throws Exception {
        props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        // Point at a refused port so a cache MISS fails fast, producing a
        // failure TokenExchangeResult — every test below that expects a
        // cache HIT would return the seeded access_token instead.
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

    /** Same SHA-256 hashing used inside the service — mirrored here so tests
     *  can seed the exact cache key the service will look up. */
    private static String hashForKey(String refreshToken) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(refreshToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    @Test
    void cacheHitReturnsStoredAccessTokenWithoutFetching() throws Exception {
        String refresh = "rt-alice";
        Object cached = cachedAccessToken("cached-access-token", "rotated-rt-alice",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey(refresh) + "|PRODUCTION", cached);

        StampsSeraOAuthService.TokenExchangeResult result =
                service.refreshToken(refresh, "cid", "secret", "PRODUCTION");

        assertTrue(result.success(), "Cache HIT must produce a successful result");
        assertEquals("cached-access-token", result.accessToken());
        assertEquals("rotated-rt-alice", result.refreshToken(),
                "The rotatedRefreshToken from the original exchange must survive on cache hit");
        assertTrue(result.expiresInSeconds() > 0,
                "expiresInSeconds must reflect remaining TTL, not zero");
    }

    @Test
    void expiredAccessTokenBypassesCacheAndFailsWhenAuctaneUnreachable() throws Exception {
        String refresh = "rt-bob";
        Object expired = cachedAccessToken("stale-token", "stale-rotated",
                Instant.now().minusSeconds(60));
        cache().put(hashForKey(refresh) + "|PRODUCTION", expired);

        StampsSeraOAuthService.TokenExchangeResult result =
                service.refreshToken(refresh, "cid", "secret", "PRODUCTION");

        assertFalse(result.success(),
                "Expired cache entry must trigger a re-fetch; when Auctane is unreachable "
                        + "the exchange fails and the caller sees the network error");
        assertNotNull(result.errorMessage());
    }

    @Test
    void accessTokenWithinRefreshMarginIsTreatedAsExpired() throws Exception {
        // Refresh margin is 60s. A token expiring in 30s is inside the
        // margin, so isValid() returns false.
        String refresh = "rt-carol";
        Object marginal = cachedAccessToken("near-expiry", "rotated",
                Instant.now().plusSeconds(30));
        cache().put(hashForKey(refresh) + "|PRODUCTION", marginal);

        StampsSeraOAuthService.TokenExchangeResult result =
                service.refreshToken(refresh, "cid", "secret", "PRODUCTION");

        assertFalse(result.success(),
                "Token within refresh margin must trigger re-fetch (which fails against "
                        + "the unreachable stub).");
    }

    @Test
    void cacheKeyIsolatesEnvironments() throws Exception {
        String refresh = "rt-shared";
        Object sandboxTok = cachedAccessToken("SANDBOX-access", "rotated-sandbox",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey(refresh) + "|SANDBOX", sandboxTok);

        // PRODUCTION request with the same refresh_token must NOT return
        // the SANDBOX-seeded access_token.
        StampsSeraOAuthService.TokenExchangeResult prod =
                service.refreshToken(refresh, "cid", "secret", "PRODUCTION");
        assertFalse(prod.success(),
                "PRODUCTION request MUST NOT receive the SANDBOX-seeded token.");

        // And SANDBOX must still hit the seeded entry.
        StampsSeraOAuthService.TokenExchangeResult sandbox =
                service.refreshToken(refresh, "cid", "secret", "SANDBOX");
        assertEquals("SANDBOX-access", sandbox.accessToken());
    }

    @Test
    void differentRefreshTokensHitDifferentSlots() throws Exception {
        Object t1 = cachedAccessToken("access-for-rt-a", "rotated-a",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey("rt-a") + "|PRODUCTION", t1);

        assertEquals("access-for-rt-a",
                service.refreshToken("rt-a", "cid", "secret", "PRODUCTION").accessToken());
        StampsSeraOAuthService.TokenExchangeResult other =
                service.refreshToken("rt-b", "cid", "secret", "PRODUCTION");
        assertFalse(other.success(),
                "Different refresh_token must NOT hit rt-a's cache slot.");
    }

    @Test
    void refreshTokenRotationLandsInDifferentCacheSlot() throws Exception {
        // Seed access_token for the ORIGINAL refresh_token.
        Object cached = cachedAccessToken("access-original", "rotated-refresh",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey("original-rt") + "|PRODUCTION", cached);

        // A call with the ORIGINAL refresh_token hits the cache.
        assertEquals("access-original",
                service.refreshToken("original-rt", "cid", "secret", "PRODUCTION").accessToken());

        // A call with the ROTATED refresh_token misses the cache (correct —
        // rotation is a fresh secret and deserves a fresh mint).
        StampsSeraOAuthService.TokenExchangeResult rotated =
                service.refreshToken("rotated-refresh", "cid", "secret", "PRODUCTION");
        assertFalse(rotated.success(),
                "Rotated refresh_token must NOT reuse the previous cache slot — it hashes "
                        + "to a different key so we miss the cache and re-fetch, as intended.");
    }

    @Test
    void nullEnvironmentTreatedAsProduction() throws Exception {
        String refresh = "rt-nullenv";
        Object tok = cachedAccessToken("prod-access", "rot",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey(refresh) + "|PRODUCTION", tok);

        // Null env normalises to PRODUCTION and hits the seeded slot.
        assertEquals("prod-access",
                service.refreshToken(refresh, "cid", "secret", null).accessToken());
        assertEquals("prod-access",
                service.refreshToken(refresh, "cid", "secret", "PRODUCTION").accessToken());
    }

    @Test
    void failedExchangeReturnsFailureAndDoesNotPoisonCache() throws Exception {
        // No seed → cache MISS → exchange fails (localhost:1 refused) →
        // caller sees failure. Crucially, the failed fetch must NOT
        // populate the cache with anything — otherwise a transient Auctane
        // outage would keep the account stuck for the whole TTL.
        StampsSeraOAuthService.TokenExchangeResult result =
                service.refreshToken("rt-fail", "cid", "secret", "PRODUCTION");
        assertFalse(result.success());
        assertNull(cache().get(hashForKey("rt-fail") + "|PRODUCTION"),
                "Failed exchange must NOT populate the cache — otherwise a transient outage "
                        + "would poison the cache for the whole TTL window.");
    }

    @Test
    void blankRefreshTokenReturnsFailureWithoutTouchingCache() throws Exception {
        StampsSeraOAuthService.TokenExchangeResult result =
                service.refreshToken("", "cid", "secret", "PRODUCTION");
        assertFalse(result.success());
        assertTrue(result.errorMessage().toLowerCase().contains("refresh_token"),
                "Blank refresh_token should surface an actionable message.");
        assertTrue(cache().isEmpty(),
                "Blank refresh_token must NOT touch the cache.");
    }

    @Test
    void clearTokenCacheDrainsAllEntries() throws Exception {
        cache().put(hashForKey("rt-x") + "|PRODUCTION",
                cachedAccessToken("x-access", "rotated-x", Instant.now().plusSeconds(3600)));
        cache().put(hashForKey("rt-y") + "|SANDBOX",
                cachedAccessToken("y-access", "rotated-y", Instant.now().plusSeconds(3600)));
        assertEquals(2, cache().size());

        service.clearTokenCache();
        assertTrue(cache().isEmpty(), "clearTokenCache() must empty the cache.");
    }

    @Test
    void refreshTokenIsNotStoredAsKey() throws Exception {
        // Security invariant: refresh_tokens are secrets. The cache key
        // must be a HASH of the refresh_token, never the token itself.
        String refresh = "very-secret-refresh-token-VALUE-that-should-never-appear-verbatim";
        Object cached = cachedAccessToken("access", "rotated",
                Instant.now().plusSeconds(3600));
        cache().put(hashForKey(refresh) + "|PRODUCTION", cached);

        // Force a lookup so the cache is exercised.
        service.refreshToken(refresh, "cid", "secret", "PRODUCTION");

        for (String key : cache().keySet()) {
            assertFalse(key.contains(refresh),
                    "Cache key '" + key + "' contains the plaintext refresh_token — "
                            + "must be a hash, not the token itself.");
        }
    }

    @Test
    void refreshMarginConstantIsSaneNotZero() throws Exception {
        Field margin = StampsSeraOAuthService.class.getDeclaredField("TOKEN_REFRESH_MARGIN_SECONDS");
        margin.setAccessible(true);
        long value = margin.getLong(null);
        assertTrue(value >= 30 && value <= 300,
                "TOKEN_REFRESH_MARGIN_SECONDS must be between 30 and 300; got " + value);
    }
}
