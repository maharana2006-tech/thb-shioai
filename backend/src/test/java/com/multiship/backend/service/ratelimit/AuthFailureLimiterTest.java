package com.multiship.backend.service.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T2 finding #6 — AuthFailureLimiter coverage.
 *
 * <p>Guards: composite key, lockout at max-failures, TTL refresh on
 * failure so a scripted loop can't wait out the window, success clears
 * the counter, Redis-absent no-op, config-disabled no-op, Redis-error
 * fail-open on reads.
 */
class AuthFailureLimiterTest {

    private ObjectProvider<StringRedisTemplate> redisProvider;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AuthFailureLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisProvider = (ObjectProvider<StringRedisTemplate>) mock(ObjectProvider.class);
        redis = mock(StringRedisTemplate.class);
        ops = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redisProvider.getIfAvailable()).thenReturn(redis);

        limiter = new AuthFailureLimiter(redisProvider);
        ReflectionTestUtils.setField(limiter, "maxFailures", 5);
        ReflectionTestUtils.setField(limiter, "windowMinutes", 15);
        ReflectionTestUtils.setField(limiter, "lockoutMinutes", 15);
    }

    @Test
    void isLockedFalseWhenCounterUnderThreshold() {
        when(ops.get(anyString())).thenReturn("3");
        assertFalse(limiter.isLocked("1.2.3.4", "alice"));
    }

    @Test
    void isLockedTrueAtThreshold() {
        when(ops.get(anyString())).thenReturn("5");
        assertTrue(limiter.isLocked("1.2.3.4", "alice"));
    }

    @Test
    void isLockedFalseWhenNoCounter() {
        when(ops.get(anyString())).thenReturn(null);
        assertFalse(limiter.isLocked("1.2.3.4", "alice"));
    }

    @Test
    void recordFailureIncrementsAndStampsWindowTtlBelowThreshold() {
        when(ops.increment(anyString())).thenReturn(2L);

        limiter.recordFailure("1.2.3.4", "alice");

        verify(ops).increment("auth-fail:1.2.3.4:alice");
        verify(redis).expire(eq("auth-fail:1.2.3.4:alice"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void recordFailureStampsLockoutTtlAtOrAboveThreshold() {
        when(ops.increment(anyString())).thenReturn(5L);

        limiter.recordFailure("1.2.3.4", "alice");

        verify(redis).expire(eq("auth-fail:1.2.3.4:alice"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void recordSuccessDeletesCounter() {
        limiter.recordSuccess("1.2.3.4", "alice");
        verify(redis).delete("auth-fail:1.2.3.4:alice");
    }

    @Test
    void keyLowercasesUsername() {
        when(ops.increment(anyString())).thenReturn(1L);
        limiter.recordFailure("1.2.3.4", "ALICE");
        verify(ops).increment("auth-fail:1.2.3.4:alice");
    }

    @Test
    void keyFallsBackToUnknownForBlankInputs() {
        when(ops.increment(anyString())).thenReturn(1L);
        limiter.recordFailure(null, "");
        verify(ops).increment("auth-fail:unknown:unknown");
    }

    @Test
    void configDisabledSkipsCheck() {
        ReflectionTestUtils.setField(limiter, "maxFailures", 0);
        // isLocked returns false without any Redis interaction
        assertFalse(limiter.isLocked("1.2.3.4", "alice"));
        verify(ops, never()).get(anyString());
    }

    @Test
    void redisAbsentIsNoOpForAllMethods() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        limiter.recordFailure("1.2.3.4", "alice");
        limiter.recordSuccess("1.2.3.4", "alice");
        assertFalse(limiter.isLocked("1.2.3.4", "alice"));
        // retryAfter falls back to configured max
        assertEquals(15 * 60, limiter.retryAfterSeconds("1.2.3.4", "alice"));
    }

    @Test
    void redisErrorOnReadIsFailOpen() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("boom"));
        // Fail-open: don't block a valid login because Redis flapped.
        assertFalse(limiter.isLocked("1.2.3.4", "alice"));
    }

    @Test
    void retryAfterReadsTtlFromRedis() {
        when(redis.getExpire(anyString())).thenReturn(300L);
        assertEquals(300, limiter.retryAfterSeconds("1.2.3.4", "alice"));
    }
}
