package com.multiship.backend.service.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Sprint 50 finding #15 (A) — per-tenant token-bucket rate limiter.
 * Keyed by uppercase clientCode; buckets live in a Caffeine cache with
 * a 1-hour idle expiry so inactive tenants don't retain memory.
 *
 * <p>Fires only for tenant-scoped callers: platform operators (ADMIN,
 * wildcard API keys) are passed through by the filter without ever
 * reaching this class, so no bucket is created for them and their
 * traffic is unbounded (matching prior behavior).
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code tenant.rate-limit.default-per-minute} — bucket capacity
 *       AND refill rate per minute. Default 100/min matches the Sprint 50
 *       target of 100 shipments/min/client — leaves headroom for reads +
 *       rate calls while still catching runaway loops.</li>
 * </ul>
 */
@Slf4j
@Service
public class TenantRateLimiter {

    private final int defaultPerMinute;
    /**
     * Sprint 51 BS-M2 — tighter budget for the AI endpoints. OpenAI-backed
     * calls cost real dollars per hit, so a runaway loop by a single tenant
     * has to be capped much lower than the general write budget. Bucket
     * type is passed through {@link #tryAcquire(String, BucketType)}; the
     * default bucket is preserved for the label + import paths.
     */
    private final int aiPerMinute;
    private final Cache<String, TokenBucket> defaultBuckets;
    private final Cache<String, TokenBucket> aiBuckets;

    /**
     * Test-friendly single-arg ctor — existing tests pass a tiny budget
     * (e.g. 2) so the drain path is exercised. Uses a large AI budget so
     * unrelated tests don't accidentally trip the AI limiter.
     */
    public TenantRateLimiter(int defaultPerMinute) {
        this(defaultPerMinute, Integer.MAX_VALUE);
    }

    /** Spring wiring: budgets are property-driven. Sprint 51 BS-M2 added
     *  the {@code ai-per-minute} bucket for the OpenAI-backed /api/v1/ai
     *  endpoints. */
    @org.springframework.beans.factory.annotation.Autowired
    public TenantRateLimiter(
            @Value("${tenant.rate-limit.default-per-minute:100}") int defaultPerMinute,
            @Value("${tenant.rate-limit.ai-per-minute:10}") int aiPerMinute) {
        if (defaultPerMinute <= 0 || aiPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "tenant.rate-limit budgets must be > 0");
        }
        this.defaultPerMinute = defaultPerMinute;
        this.aiPerMinute = aiPerMinute;
        this.defaultBuckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(10_000)
                .build();
        this.aiBuckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(10_000)
                .build();
    }

    /** Which bucket to charge — general write or AI-assist. */
    public enum BucketType { DEFAULT, AI }

    /** Outcome of one acquire attempt. */
    public record Outcome(boolean allowed, long retryAfterSeconds) {
        public static Outcome ok() { return new Outcome(true, 0); }
        public static Outcome denied(long secs) { return new Outcome(false, secs); }
    }

    /**
     * Try to consume one token for the given tenant on the DEFAULT bucket.
     * Kept for callers that don't care about the AI-vs-write split.
     *
     * @param tenantClientCode caller's tenant (already normalized upper-case).
     */
    public Outcome tryAcquire(String tenantClientCode) {
        return tryAcquire(tenantClientCode, BucketType.DEFAULT);
    }

    /**
     * Try to consume one token for the given tenant on the requested bucket.
     * AI paths get their own bucket (10/min default) so an OpenAI runaway
     * doesn't consume the general 100/min write budget and vice-versa.
     */
    public Outcome tryAcquire(String tenantClientCode, BucketType type) {
        Cache<String, TokenBucket> bucketCache = type == BucketType.AI ? aiBuckets : defaultBuckets;
        int budget = type == BucketType.AI ? aiPerMinute : defaultPerMinute;
        TokenBucket bucket = bucketCache.get(tenantClientCode,
                k -> new TokenBucket(budget, budget));
        if (bucket.tryAcquire()) return Outcome.ok();
        long waitMs = bucket.retryAfterMs();
        // Round UP so a 500 ms wait reports Retry-After: 1 rather than 0
        // (integrators reading a 0 loop-immediately).
        long seconds = Math.max(1, (waitMs + 999) / 1000);
        log.warn("Rate limit hit — tenant {} exhausted {} bucket (retry in {}s)",
                tenantClientCode, type, seconds);
        return Outcome.denied(seconds);
    }

    /** For tests + admin visibility — checks the DEFAULT bucket. */
    public long availableTokens(String tenantClientCode) {
        TokenBucket bucket = defaultBuckets.getIfPresent(tenantClientCode);
        return bucket == null ? defaultPerMinute : bucket.availableTokens();
    }

    /** For tests — drop cached buckets. */
    public void reset() {
        defaultBuckets.invalidateAll();
        aiBuckets.invalidateAll();
    }
}
