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
    private final Cache<String, TokenBucket> buckets;

    public TenantRateLimiter(
            @Value("${tenant.rate-limit.default-per-minute:100}") int defaultPerMinute) {
        if (defaultPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "tenant.rate-limit.default-per-minute must be > 0");
        }
        this.defaultPerMinute = defaultPerMinute;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(10_000)
                .build();
    }

    /** Outcome of one acquire attempt. */
    public record Outcome(boolean allowed, long retryAfterSeconds) {
        public static Outcome ok() { return new Outcome(true, 0); }
        public static Outcome denied(long secs) { return new Outcome(false, secs); }
    }

    /**
     * Try to consume one token for the given tenant. Creates the bucket
     * on first hit; subsequent calls reuse it.
     *
     * @param tenantClientCode caller's tenant (already normalized upper-case).
     */
    public Outcome tryAcquire(String tenantClientCode) {
        TokenBucket bucket = buckets.get(tenantClientCode,
                k -> new TokenBucket(defaultPerMinute, defaultPerMinute));
        if (bucket.tryAcquire()) return Outcome.ok();
        long waitMs = bucket.retryAfterMs();
        // Round UP so a 500 ms wait reports Retry-After: 1 rather than 0
        // (integrators reading a 0 loop-immediately).
        long seconds = Math.max(1, (waitMs + 999) / 1000);
        log.warn("Rate limit hit — tenant {} exhausted bucket (retry in {}s)",
                tenantClientCode, seconds);
        return Outcome.denied(seconds);
    }

    /** For tests + admin visibility. */
    public long availableTokens(String tenantClientCode) {
        TokenBucket bucket = buckets.getIfPresent(tenantClientCode);
        return bucket == null ? defaultPerMinute : bucket.availableTokens();
    }

    /** For tests — drop cached buckets. */
    public void reset() {
        buckets.invalidateAll();
    }
}
