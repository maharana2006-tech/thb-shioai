package com.multiship.backend.service.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 50 finding #15 (A) — token bucket unit tests. Refill math is
 * covered by a time-based test that sleeps briefly; the concurrency test
 * asserts atomicity (N threads each draining once totals exactly capacity).
 */
class TokenBucketTest {

    @Test
    void constructor_rejectsNonPositiveArgs() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 60));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1, 60));
    }

    @Test
    void tryAcquire_drainsToEmptyThenDenies() {
        TokenBucket bucket = new TokenBucket(3, 60);
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }

    @Test
    void retryAfterMs_isZeroWhenTokensAvailable_positiveWhenEmpty() {
        TokenBucket bucket = new TokenBucket(2, 60); // ~1 token per second
        assertEquals(0, bucket.retryAfterMs());
        bucket.tryAcquire();
        bucket.tryAcquire();
        long wait = bucket.retryAfterMs();
        // At 60/min = 1/s, next token should arrive within ~1000ms.
        assertTrue(wait > 0 && wait <= 1100, "expected 0..1100, got " + wait);
    }

    @Test
    void refill_addsTokensProportionalToElapsedTime() throws InterruptedException {
        // 6000/min = 100/s = 1 token per 10 ms. Sleeping 60 ms should add ~6.
        TokenBucket bucket = new TokenBucket(100, 6000);
        // Drain to 0
        for (int i = 0; i < 100; i++) bucket.tryAcquire();
        assertEquals(0, bucket.availableTokens());

        Thread.sleep(60);
        long refilled = bucket.availableTokens();
        // Allow slack for scheduler jitter — expect ~6 but accept 4..15.
        assertTrue(refilled >= 4 && refilled <= 15,
                "expected 4..15 tokens after 60 ms at 100/s, got " + refilled);
    }

    @Test
    void refill_capsAtCapacity() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(5, 60_000); // 1000/s
        // Idle for a while — should NOT overflow past capacity even though
        // the refill math would allow thousands.
        Thread.sleep(50);
        assertEquals(5, bucket.availableTokens());
    }

    @Test
    void tryAcquire_isThreadSafe() throws InterruptedException {
        int capacity = 500;
        // Set refill absurdly low so nothing new arrives during the burst.
        TokenBucket bucket = new TokenBucket(capacity, 1);
        int threads = 50;
        int perThread = 20; // total attempts = 1000, but only 500 should succeed
        var pool = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        if (bucket.tryAcquire()) successes.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        // Small tolerance for the fractional-refill during the burst window.
        assertTrue(successes.get() >= capacity && successes.get() <= capacity + 5,
                "expected ~" + capacity + " successes, got " + successes.get());
    }
}
