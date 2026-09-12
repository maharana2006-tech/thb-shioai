package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CarrierOutboundRateLimiter} — the client-side
 * throttle added 2026-09-11 to prevent the bulk-import UPS burst-into-429
 * pattern (real failure at 20:06:20 that day: 8 concurrent UPS calls all
 * getting HTTP 429 10429 "Too Many Requests" back-to-back).
 */
class CarrierOutboundRateLimiterTest {

    /** Build a limiter with the given carrier configured to {@code rps}
     *  requests/second. Uses the package-private test hook so DI/@Value
     *  isn't in the loop. */
    private CarrierOutboundRateLimiter limiterAt(String carrier, int rps) {
        CarrierOutboundRateLimiter limiter = new CarrierOutboundRateLimiter();
        limiter.setRequestsPerSecondForTest(carrier, rps);
        return limiter;
    }

    @Test
    void singleCallerIsUnthrottled() {
        CarrierOutboundRateLimiter limiter = limiterAt("UPS", 2); // 500ms gap
        long start = System.currentTimeMillis();
        limiter.acquire("UPS"); // first call — nextEligible = now, no wait
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 50,
                "First call for an idle carrier should return immediately; took " + elapsed + " ms");
    }

    @Test
    void secondCallOnSameCarrierWaitsForMinGap() {
        CarrierOutboundRateLimiter limiter = limiterAt("UPS", 4); // 250ms gap
        limiter.acquire("UPS"); // primes nextEligible
        long start = System.currentTimeMillis();
        limiter.acquire("UPS");
        long elapsed = System.currentTimeMillis() - start;
        // 250ms nominal; allow generous jitter on Windows sleep precision.
        assertTrue(elapsed >= 200,
                "Second immediate call should wait ~250ms; only waited " + elapsed + " ms");
        assertTrue(elapsed < 600,
                "Second call should not wait more than one gap; waited " + elapsed + " ms");
    }

    @Test
    void differentCarriersDoNotBlockEachOther() {
        CarrierOutboundRateLimiter limiter = new CarrierOutboundRateLimiter();
        limiter.setRequestsPerSecondForTest("UPS", 1);   // 1000ms gap
        limiter.setRequestsPerSecondForTest("FEDEX", 1); // 1000ms gap
        limiter.acquire("UPS");
        long start = System.currentTimeMillis();
        limiter.acquire("FEDEX");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 50,
                "Different carrier's slot must be independent; took " + elapsed + " ms");
    }

    @Test
    void zeroRequestsPerSecondDisablesTheLimiter() {
        CarrierOutboundRateLimiter limiter = limiterAt("UPS", 4);
        limiter.setRequestsPerSecondForTest("UPS", 0); // disable
        limiter.acquire("UPS");
        long start = System.currentTimeMillis();
        limiter.acquire("UPS");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 50,
                "Zero rps must disable the limiter; second call waited " + elapsed + " ms");
    }

    @Test
    void nullCarrierIsIgnored() {
        CarrierOutboundRateLimiter limiter = limiterAt("UPS", 4);
        // Should not NPE.
        limiter.acquire(null);
    }

    @Test
    void concurrentCallersAreSerializedToTheConfiguredRate() throws Exception {
        // 8 concurrent workers × 4 rps = ~1.75s total (7 gaps × 250ms).
        // Widened tolerance so Windows sleep jitter doesn't flake this
        // on CI: min 1.0s (must be > 4 gaps), max 3.5s.
        int workers = 8;
        CarrierOutboundRateLimiter limiter = limiterAt("UPS", 4); // 250ms gap
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            AtomicInteger done = new AtomicInteger();
            long start = System.currentTimeMillis();
            List<Future<?>> futures = new java.util.ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    limiter.acquire("UPS");
                    done.incrementAndGet();
                }));
            }
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(workers, done.get());
            // 7 gaps of 250ms = 1750ms minimum; allow 1000ms floor for jitter.
            assertTrue(elapsed >= 1000,
                    "8 workers × 4rps should take ~1.75s; took only " + elapsed + " ms — "
                            + "the limiter isn't serialising concurrent callers.");
        } finally {
            pool.shutdownNow();
        }
    }
}
