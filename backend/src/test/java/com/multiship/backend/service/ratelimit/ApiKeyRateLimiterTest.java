package com.multiship.backend.service.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 1 finding #15 — rate limiter unit coverage. Uses a
 * scripted StringRedisTemplate.opsForValue().increment stub that
 * mirrors real INCR semantics (counter climbs by 1 per call), so the
 * limit-crossing behaviour is exercised without spinning up Redis.
 */
class ApiKeyRateLimiterTest {

    private static final int LIMIT = 5;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ApiKeyRateLimiter limiter;
    private AtomicLong counter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // Every INCR bumps the shared counter — this matches how Redis
        // would return a monotonically-increasing count for the same key.
        counter = new AtomicLong(0);
        when(valueOps.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());
        // TTL calls succeed silently — return value not consumed.
        // Explicit Duration.class disambiguates the overload against
        // expire(K, Expiration) also on RedisTemplate.
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        limiter = new ApiKeyRateLimiter(provider);
        inject("requestsPerMinute", LIMIT);
    }

    @Test
    void firstNRequestsAreAllowed() {
        for (int i = 0; i < LIMIT; i++) {
            ApiKeyRateLimiter.Decision d = limiter.tryAcquire(42L);
            assertTrue(d.allowed(), "request " + (i + 1) + " should be allowed (count=" + d.currentCount() + ")");
        }
    }

    @Test
    void requestBeyondLimitIsDeniedWithRetryAfter() {
        for (int i = 0; i < LIMIT; i++) limiter.tryAcquire(42L);
        ApiKeyRateLimiter.Decision d = limiter.tryAcquire(42L);
        assertFalse(d.allowed(), "the (limit+1)th request must be denied");
        assertEquals(LIMIT, d.limit());
        assertTrue(d.retryAfterSeconds() > 0 && d.retryAfterSeconds() <= 60,
                "retryAfterSeconds must be within the current minute window");
    }

    @Test
    void nullApiKeyIdSkipsTheLimit() {
        // Internal wiring / unauthenticated request → allow, don't touch Redis.
        for (int i = 0; i < LIMIT * 3; i++) {
            assertTrue(limiter.tryAcquire(null).allowed());
        }
    }

    @Test
    void limitOfZeroDisablesLimiter() throws Exception {
        inject("requestsPerMinute", 0);
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire(42L).allowed(),
                    "requests-per-minute=0 must disable the limiter entirely");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisAbsenceIsFailOpen() throws Exception {
        // Provider returns null when Spring couldn't wire a Redis template.
        ObjectProvider<StringRedisTemplate> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        ApiKeyRateLimiter offline = new ApiKeyRateLimiter(empty);
        Field f = ApiKeyRateLimiter.class.getDeclaredField("requestsPerMinute");
        f.setAccessible(true);
        f.set(offline, LIMIT);

        // No Redis → allow every request. Better than DoS'ing legitimate
        // traffic on a monitoring degradation.
        for (int i = 0; i < LIMIT * 3; i++) {
            assertTrue(offline.tryAcquire(42L).allowed());
        }
    }

    @Test
    void redisThrowsIsFailOpen() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("connection lost"));
        // Should not throw; should degrade to allow.
        ApiKeyRateLimiter.Decision d = limiter.tryAcquire(42L);
        assertTrue(d.allowed(), "Redis blip must not deny legitimate traffic");
    }

    /* -------- Sprint 50 PR L: countKnown flag on Redis-unavailable -------- */

    @Test
    @SuppressWarnings("unchecked")
    void redisAbsenceReportsCountUnknown() throws Exception {
        // Signals the interceptor to OMIT X-RateLimit-Remaining rather
        // than emit a lying full-budget number that would trick integrators
        // into hammering the API during an outage.
        ObjectProvider<StringRedisTemplate> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        ApiKeyRateLimiter offline = new ApiKeyRateLimiter(empty);
        Field f = ApiKeyRateLimiter.class.getDeclaredField("requestsPerMinute");
        f.setAccessible(true);
        f.set(offline, LIMIT);

        ApiKeyRateLimiter.Decision d = offline.tryAcquire(42L);
        assertTrue(d.allowed(), "no Redis → fail-open, still allowed");
        assertFalse(d.countKnown(), "no Redis → countKnown must be false");
    }

    @Test
    void redisThrowsReportsCountUnknown() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("connection lost"));
        ApiKeyRateLimiter.Decision d = limiter.tryAcquire(42L);
        assertTrue(d.allowed(), "Redis blip → fail-open, still allowed");
        assertFalse(d.countKnown(), "Redis blip → countKnown must be false so header omits");
    }

    @Test
    void redisHealthyReportsCountKnown() {
        ApiKeyRateLimiter.Decision d = limiter.tryAcquire(42L);
        assertTrue(d.allowed());
        assertTrue(d.countKnown(), "healthy Redis → countKnown=true so header emits");
        assertEquals(1, d.currentCount(), "first request bumps to 1");
    }

    private void inject(String field, int value) throws Exception {
        Field f = ApiKeyRateLimiter.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(limiter, value);
    }
}
