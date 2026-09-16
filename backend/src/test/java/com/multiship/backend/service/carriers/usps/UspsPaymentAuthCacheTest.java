package com.multiship.backend.service.carriers.usps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link UspsPaymentAuthCache}.
 *
 * <p>The payment-auth token gates USPS's label endpoint — every printed
 * label debits the tenant's EPS account, so USPS re-authorises the
 * (CRID, MID, account) tuple on every mint. The cache lets N concurrent
 * bulk-label workers on the same tenant share one authorisation instead
 * of firing N POSTs to {@code /payments/v3/payment-authorization}.
 *
 * <p>Invariants pinned here:
 * <ul>
 *   <li>Different tenants (any of CRID / MID / account differ) get
 *       different cache slots — no cross-tenant token reuse.</li>
 *   <li>Same tenant tuple returns the same cached token.</li>
 *   <li>TTL is respected — expired entries force a refresh.</li>
 *   <li>Failed mints don't poison the cache.</li>
 *   <li>The wire-body shape names all four roles (PAYER / LABEL_OWNER
 *       / RATE_HOLDER / PLATFORM) with the tenant's CRID + MID + account.</li>
 * </ul>
 */
class UspsPaymentAuthCacheTest {

    private UspsPaymentAuthCache cache;
    private Field cacheField;

    @BeforeEach
    void setUp() throws Exception {
        cache = new UspsPaymentAuthCache(new ObjectMapper());
        cacheField = UspsPaymentAuthCache.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Object, Object> internalMap() throws Exception {
        return (ConcurrentHashMap<Object, Object>) cacheField.get(cache);
    }

    private static Object cachedToken(String token, Instant expiresAt) throws Exception {
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache$CachedToken");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(token, expiresAt);
    }

    private static Object cacheKey(String crid, String mid, String acct, String env) throws Exception {
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache$CacheKey");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(crid, mid, acct, env);
    }

    @Test
    void sameTenantTupleHitsCachedToken() throws Exception {
        Object valid = cachedToken("tenant-A-paymentauth",
                Instant.now().plusSeconds(7200));
        internalMap().put(cacheKey("CRID-A", "MID-A", "ACCT-A", "PRODUCTION"), valid);

        Optional<String> returned = cache.getToken("CRID-A", "MID-A", "ACCT-A",
                "oauth-bearer", "PRODUCTION");
        assertTrue(returned.isPresent(), "seeded cache slot must be hit");
        assertEquals("tenant-A-paymentauth", returned.get());
    }

    @Test
    void differentTenantsGetSeparateCacheSlots() throws Exception {
        Object tokA = cachedToken("A-tok", Instant.now().plusSeconds(7200));
        Object tokB = cachedToken("B-tok", Instant.now().plusSeconds(7200));
        Object tokC = cachedToken("C-tok", Instant.now().plusSeconds(7200));
        internalMap().put(cacheKey("CRID-A", "MID-A", "ACCT-A", "PRODUCTION"), tokA);
        internalMap().put(cacheKey("CRID-B", "MID-A", "ACCT-A", "PRODUCTION"), tokB);
        internalMap().put(cacheKey("CRID-A", "MID-B", "ACCT-A", "PRODUCTION"), tokC);

        assertEquals("A-tok",
                cache.getToken("CRID-A", "MID-A", "ACCT-A", "oauth", "PRODUCTION").orElse(null));
        assertEquals("B-tok",
                cache.getToken("CRID-B", "MID-A", "ACCT-A", "oauth", "PRODUCTION").orElse(null));
        assertEquals("C-tok",
                cache.getToken("CRID-A", "MID-B", "ACCT-A", "oauth", "PRODUCTION").orElse(null));
        assertEquals(3, cache.size(),
                "three distinct tenant tuples → three cache slots");
    }

    @Test
    void differentAccountsGetSeparateSlots() throws Exception {
        Object tokX = cachedToken("X-tok", Instant.now().plusSeconds(7200));
        Object tokY = cachedToken("Y-tok", Instant.now().plusSeconds(7200));
        internalMap().put(cacheKey("CRID", "MID", "ACCT-X", "PRODUCTION"), tokX);
        internalMap().put(cacheKey("CRID", "MID", "ACCT-Y", "PRODUCTION"), tokY);

        assertEquals("X-tok",
                cache.getToken("CRID", "MID", "ACCT-X", "oauth", "PRODUCTION").orElse(null));
        assertEquals("Y-tok",
                cache.getToken("CRID", "MID", "ACCT-Y", "oauth", "PRODUCTION").orElse(null));
    }

    @Test
    void environmentIsolatesTenantSlots() throws Exception {
        Object sandboxTok = cachedToken("sandbox-tok",
                Instant.now().plusSeconds(7200));
        internalMap().put(cacheKey("CRID", "MID", "ACCT", "SANDBOX"), sandboxTok);

        // Same tenant tuple, different env → separate slot. Miss +
        // unreachable USPS host → empty.
        assertTrue(cache.getToken("CRID", "MID", "ACCT", "oauth", "PRODUCTION").isEmpty(),
                "PRODUCTION must not reuse a SANDBOX-scoped token.");
        assertEquals("sandbox-tok",
                cache.getToken("CRID", "MID", "ACCT", "oauth", "SANDBOX").orElse(null));
    }

    @Test
    void expiredTokenBypassesCache() throws Exception {
        Object expired = cachedToken("stale-tok", Instant.now().minusSeconds(30));
        internalMap().put(cacheKey("CRID", "MID", "ACCT", "PRODUCTION"), expired);

        Optional<String> returned = cache.getToken("CRID", "MID", "ACCT",
                "oauth", "PRODUCTION");
        // Expired → force refresh → fetch fails (no live host) → empty.
        assertTrue(returned.isEmpty(),
                "Expired entry must not be returned; fetch failure → empty.");
    }

    @Test
    void tokenInsideRefreshMarginTreatedAsExpired() throws Exception {
        // Refresh margin is 60s. A token expiring in 30s is inside the
        // margin, so isValid() must be false.
        Object marginal = cachedToken("near-expiry", Instant.now().plusSeconds(30));
        internalMap().put(cacheKey("CRID-M", "MID-M", "ACCT-M", "PRODUCTION"), marginal);

        Optional<String> returned = cache.getToken("CRID-M", "MID-M", "ACCT-M",
                "oauth", "PRODUCTION");
        assertTrue(returned.isEmpty(),
                "Token inside refresh margin must be re-fetched.");
    }

    @Test
    void missingIdentifiersShortCircuit() {
        assertTrue(cache.getToken("", "MID", "ACCT", "oauth", "PRODUCTION").isEmpty(),
                "blank CRID must skip the call");
        assertTrue(cache.getToken("CRID", "", "ACCT", "oauth", "PRODUCTION").isEmpty(),
                "blank MID must skip the call");
        assertTrue(cache.getToken("CRID", "MID", "", "oauth", "PRODUCTION").isEmpty(),
                "blank account must skip the call");
        assertTrue(cache.getToken("CRID", "MID", "ACCT", "", "PRODUCTION").isEmpty(),
                "blank OAuth token must skip the call");
        assertTrue(cache.getToken(null, null, null, null, "PRODUCTION").isEmpty());
    }

    @Test
    void failedFetchDoesNotPoisonCache() throws Exception {
        // No seed → cache miss → fetch fails → empty. Cache must remain
        // empty so a subsequent successful mint can populate it.
        Optional<String> returned = cache.getToken("CRID-poison", "MID-poison",
                "ACCT-poison", "oauth", "PRODUCTION");
        assertTrue(returned.isEmpty());
        assertNull(internalMap().get(
                        cacheKey("CRID-poison", "MID-poison", "ACCT-poison", "PRODUCTION")),
                "failed fetch must not leave a phantom entry");
    }

    @Test
    void clearDrainsCache() throws Exception {
        internalMap().put(cacheKey("A", "A", "A", "PRODUCTION"),
                cachedToken("t1", Instant.now().plusSeconds(7200)));
        internalMap().put(cacheKey("B", "B", "B", "SANDBOX"),
                cachedToken("t2", Instant.now().plusSeconds(7200)));
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void payloadShape_hasAllFourRolesWithTenantIds() {
        // Contract with USPS: the payment-authorization request MUST name
        // PAYER, LABEL_OWNER, RATE_HOLDER, PLATFORM. Missing any → 400.
        Map<String, Object> body = UspsPaymentAuthCache.buildPaymentAuthPayload(
                "CRID-Z", "MID-Z", "ACCT-Z");

        Object rolesObj = body.get("roles");
        assertNotNull(rolesObj, "body must include roles[]");
        assertTrue(rolesObj instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) rolesObj;
        assertEquals(4, roles.size(), "USPS requires exactly 4 role entries.");

        List<String> roleNames = roles.stream()
                .map(r -> (String) r.get("roleName"))
                .toList();
        assertTrue(roleNames.contains("PAYER"));
        assertTrue(roleNames.contains("LABEL_OWNER"));
        assertTrue(roleNames.contains("RATE_HOLDER"));
        assertTrue(roleNames.contains("PLATFORM"));

        for (Map<String, Object> role : roles) {
            assertEquals("CRID-Z", role.get("CRID"),
                    "every role must carry the tenant's CRID");
            assertEquals("MID-Z", role.get("MID"),
                    "every role must carry the tenant's MID");
            assertEquals("ACCT-Z", role.get("accountNumber"),
                    "every role must carry the EPS account number");
            assertEquals("EPS", role.get("accountType"),
                    "USPS Direct always debits EPS accounts");
        }
    }

    @Test
    void backoffDelayIncreasesExponentially() {
        long d0 = UspsPaymentAuthCache.backoffDelayMillis(0);
        long d1 = UspsPaymentAuthCache.backoffDelayMillis(1);
        long d2 = UspsPaymentAuthCache.backoffDelayMillis(2);
        assertTrue(d0 >= 2_000 && d0 <= 2_500, "attempt 0 ~= 2s");
        assertTrue(d1 >= 4_000 && d1 <= 4_500, "attempt 1 ~= 4s");
        assertTrue(d2 >= 8_000 && d2 <= 8_500, "attempt 2 ~= 8s");
    }
}
