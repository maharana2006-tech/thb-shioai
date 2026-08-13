package com.multiship.backend.service;

import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T2 finding #5 — coverage for the JWT revocation primitive.
 * Guards: DB-backed tv lookup + cache, bumpTokenVersion invalidates the
 * cache, Redis-absent path is a graceful no-op, Redis-error path
 * fail-opens on read (correctness comes from the tv check).
 */
class TokenRevocationServiceTest {

    private UserRepository userRepository;
    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> redisProvider =
            (ObjectProvider<StringRedisTemplate>) mock(ObjectProvider.class);
    private TokenRevocationService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        redisProvider = (ObjectProvider<StringRedisTemplate>) mock(ObjectProvider.class);
        // Default: no Redis wired — the service must still work.
        when(redisProvider.getIfAvailable()).thenReturn(null);
        svc = new TokenRevocationService(userRepository, redisProvider);
    }

    private User user(String username, long tv) {
        return User.builder().id(1L).username(username).tokenVersion(tv).build();
    }

    @Test
    void currentTokenVersionReturns0ForUnknownUser() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertEquals(0L, svc.currentTokenVersion("ghost"));
    }

    @Test
    void currentTokenVersionReadsDbAndCaches() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice", 7L)));

        assertEquals(7L, svc.currentTokenVersion("alice"));
        assertEquals(7L, svc.currentTokenVersion("alice"));

        // Second call hits the cache, not the DB.
        verify(userRepository, times(1)).findByUsername("alice");
    }

    @Test
    void bumpTokenVersionIncrementsAndInvalidatesCache() {
        User alice = user("alice", 3L);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        // Warm cache
        assertEquals(3L, svc.currentTokenVersion("alice"));

        svc.bumpTokenVersion(alice);
        assertEquals(4L, alice.getTokenVersion());
        verify(userRepository).save(alice);

        // Next read should hit DB again (cache invalidated).
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice", 4L)));
        assertEquals(4L, svc.currentTokenVersion("alice"));
        verify(userRepository, times(2)).findByUsername("alice");
    }

    @Test
    void bumpTokenVersionHandlesNullOrMissingUser() {
        svc.bumpTokenVersion(null);
        svc.bumpTokenVersion(User.builder().username(null).build());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blacklistJtiNoOpWhenRedisAbsent() {
        // No Redis (setUp default) — must not throw.
        svc.blacklistJti("some-jti", Instant.now().plus(1, ChronoUnit.HOURS));
        assertFalse(svc.isJtiBlacklisted("some-jti"));
    }

    @Test
    void blacklistJtiWritesToRedisWithRemainingTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redisProvider.getIfAvailable()).thenReturn(redis);

        Instant future = Instant.now().plus(30, ChronoUnit.MINUTES);
        svc.blacklistJti("abc-123", future);

        // Verify SET was called under revoked-jti: prefix. We don't
        // assert the exact TTL because it depends on wall-clock timing.
        verify(ops).set(eq("revoked-jti:abc-123"), anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    void blacklistJtiSkipsAlreadyExpired() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);

        svc.blacklistJti("stale", Instant.now().minus(1, ChronoUnit.MINUTES));

        verify(redis, never()).opsForValue();
    }

    @Test
    void isJtiBlacklistedTrueWhenKeyPresent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.hasKey("revoked-jti:zzz")).thenReturn(Boolean.TRUE);

        assertTrue(svc.isJtiBlacklisted("zzz"));
    }

    @Test
    void isJtiBlacklistedFailOpenOnRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("boom"));

        // Fail-open: don't block valid callers because Redis flapped.
        // Correctness safety net is the DB tv check.
        assertFalse(svc.isJtiBlacklisted("xyz"));
    }
}
