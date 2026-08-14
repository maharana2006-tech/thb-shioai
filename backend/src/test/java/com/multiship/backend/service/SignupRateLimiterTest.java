package com.multiship.backend.service;

import com.multiship.backend.repository.SignupAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 BS-L3 — verifies the SignupRateLimiter no longer allows
 * concurrent callers to slip past the cap by racing the read-check-write.
 * Uses an in-memory ConcurrentHashMap-backed Redis stub so 100 concurrent
 * threads can hammer the same email/ip; only {@code emailPerHour} of them
 * should be allowed through.
 */
class SignupRateLimiterTest {

    private SignupAttemptRepository repository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectProvider<StringRedisTemplate> redisProvider;
    private ConcurrentHashMap<String, AtomicInteger> store;
    private SignupRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(SignupAttemptRepository.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        redisProvider = mock(ObjectProvider.class);
        store = new ConcurrentHashMap<>();

        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(valueOps);
        // Concurrent-safe increment against the in-memory map so 100 threads
        // can call at once and each get a monotonically increasing count.
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return (long) store.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        });
        // expire is a no-op for the test (TTL tracking isn't the SUT).
        when(redis.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);

        limiter = new SignupRateLimiter(repository, redisProvider);
        ReflectionTestUtils.setField(limiter, "emailPerHour", 5);
        ReflectionTestUtils.setField(limiter, "ipPerHour", 100);
    }

    @Test
    void serial_first5CallsAllowed_6thDenied() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.isAllowed("target@example.com", "1.2.3.4"),
                    "hit " + i + " should be under the email cap");
        }
        assertFalse(limiter.isAllowed("target@example.com", "1.2.3.4"),
                "6th hit for the same email must be denied");
    }

    @Test
    void concurrent100Callers_neverExceedsEmailCap() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Boolean> outcomes = new ArrayList<>();
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    boolean ok = limiter.isAllowed("race@example.com", "10.0.0.1");
                    synchronized (outcomes) { outcomes.add(ok); }
                    if (ok) allowed.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        startGate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "pool must complete");

        // The whole point of BS-L3: no matter how many concurrent callers,
        // the number of "allowed" outcomes must never exceed the cap.
        // Previously the TOCTOU race let this number climb well past 5.
        assertTrue(allowed.get() <= 5,
                "atomic gate must never let more than emailPerHour=5 through, got " + allowed.get());
        assertEquals(5, allowed.get(),
                "exactly emailPerHour=5 callers should have been allowed under load");
    }

    @Test
    void redisAbsent_fallsBackToDbPath() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        // DB path returns "allowed" when no rows exist.
        when(repository.countByEmailAndCreatedAtAfter(anyString(), any(LocalDateTime.class))).thenReturn(0L);
        when(repository.countByIpAndCreatedAtAfter(anyString(), any(LocalDateTime.class))).thenReturn(0L);
        assertTrue(limiter.isAllowed("target@example.com", "1.2.3.4"));
    }
}
