package com.multiship.backend.service.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantRateLimiterTest {

    @Test
    void constructor_rejectsNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class, () -> new TenantRateLimiter(0));
    }

    @Test
    void firstCall_createsBucketAndAllows() {
        TenantRateLimiter limiter = new TenantRateLimiter(10);
        TenantRateLimiter.Outcome o = limiter.tryAcquire("ACME");
        assertTrue(o.allowed());
        assertEquals(0, o.retryAfterSeconds());
    }

    @Test
    void tenantsAreIsolated_oneStarvedDoesNotBlockAnother() {
        TenantRateLimiter limiter = new TenantRateLimiter(3);
        // Drain ACME to zero
        for (int i = 0; i < 3; i++) assertTrue(limiter.tryAcquire("ACME").allowed());
        assertFalse(limiter.tryAcquire("ACME").allowed());
        // OTHER should be untouched
        assertTrue(limiter.tryAcquire("OTHER").allowed());
        assertTrue(limiter.tryAcquire("OTHER").allowed());
    }

    @Test
    void deniedOutcome_reportsPositiveRetryAfter() {
        TenantRateLimiter limiter = new TenantRateLimiter(1);
        assertTrue(limiter.tryAcquire("ACME").allowed());
        TenantRateLimiter.Outcome o = limiter.tryAcquire("ACME");
        assertFalse(o.allowed());
        assertTrue(o.retryAfterSeconds() >= 1,
                "Retry-After should round up to >= 1 second, got " + o.retryAfterSeconds());
    }

    @Test
    void availableTokens_reportsFullBudgetForUnknownTenant() {
        TenantRateLimiter limiter = new TenantRateLimiter(42);
        // No calls yet — treat as fresh bucket
        assertEquals(42, limiter.availableTokens("NEVER_SEEN"));
    }

    @Test
    void reset_dropsAllBuckets() {
        TenantRateLimiter limiter = new TenantRateLimiter(1);
        limiter.tryAcquire("ACME");
        assertFalse(limiter.tryAcquire("ACME").allowed());
        limiter.reset();
        assertTrue(limiter.tryAcquire("ACME").allowed());
    }

    /* -------- Sprint 51 BS-M2 — AI bucket tests -------- */

    @Test
    void aiBucket_isIndependentFromDefaultBucket() {
        // AI budget=2, default budget=10; draining AI must not touch DEFAULT.
        TenantRateLimiter limiter = new TenantRateLimiter(10, 2);
        assertTrue(limiter.tryAcquire("ACME", TenantRateLimiter.BucketType.AI).allowed());
        assertTrue(limiter.tryAcquire("ACME", TenantRateLimiter.BucketType.AI).allowed());
        assertFalse(limiter.tryAcquire("ACME", TenantRateLimiter.BucketType.AI).allowed(),
                "AI bucket exhausted after 2 hits");
        // DEFAULT still has full budget — the AI drain must not have leaked.
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("ACME", TenantRateLimiter.BucketType.DEFAULT).allowed());
        }
    }

    @Test
    void constructor_rejectsNonPositiveAiBudget() {
        assertThrows(IllegalArgumentException.class, () -> new TenantRateLimiter(10, 0));
    }
}
