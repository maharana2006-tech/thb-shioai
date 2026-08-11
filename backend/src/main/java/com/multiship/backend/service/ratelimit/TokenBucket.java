package com.multiship.backend.service.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Sprint 50 finding #15 (A) — classic token bucket. Refills linearly at
 * {@code refillPerMinute / 60_000} tokens per millisecond, capped at
 * {@code capacity}. Thread-safe via a single {@link AtomicLong} that
 * packs "tokens available now" and "last refill epoch millis" — no
 * lock, no allocation on the hot path.
 *
 * <p>Chosen over Bucket4j to avoid pulling in a new dependency for a
 * ~40-LOC piece the codebase already had precedent for
 * ({@code SignupRateLimiter}). Semantics match Bucket4j's
 * {@code Bandwidth.simple(capacity, Duration.ofMinutes(1))}.
 */
public final class TokenBucket {

    private final long capacity;
    private final double tokensPerMs;
    /** Encoded state: {@code lastRefillMs} + a separate {@link #tokens} atomic.
     *  Two atomics because a 64-bit pack would need to bit-fold a double
     *  (fractional tokens) and I want the refill math obvious. */
    private final AtomicLong lastRefillMs;
    private final AtomicLong tokens;

    public TokenBucket(long capacity, long refillPerMinute) {
        if (capacity <= 0 || refillPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "capacity + refillPerMinute must both be > 0");
        }
        this.capacity = capacity;
        this.tokensPerMs = refillPerMinute / 60_000.0;
        this.lastRefillMs = new AtomicLong(System.currentTimeMillis());
        this.tokens = new AtomicLong(capacity);
    }

    /**
     * @return true if a token was consumed; false when the bucket is empty.
     * On false, {@link #retryAfterMs()} returns how long until the next
     * token becomes available.
     */
    public boolean tryAcquire() {
        refill();
        // Decrement only if there is at least one token — CAS loop so
        // concurrent callers don't drive the count negative.
        while (true) {
            long current = tokens.get();
            if (current <= 0) return false;
            if (tokens.compareAndSet(current, current - 1)) return true;
        }
    }

    /**
     * @return milliseconds until at least one token will be available,
     * assuming no further consumption. Zero when a token is available now.
     */
    public long retryAfterMs() {
        refill();
        if (tokens.get() > 0) return 0;
        // Time to accrue one full token at the configured refill rate.
        return (long) Math.ceil(1.0 / tokensPerMs);
    }

    /** For tests + admin observability. */
    public long availableTokens() {
        refill();
        return tokens.get();
    }

    /**
     * Add tokens proportional to elapsed time since last refill. Uses CAS
     * on the timestamp so only one racer performs the addition per tick;
     * others read the updated value on their next call.
     */
    private void refill() {
        long now = System.currentTimeMillis();
        long last = lastRefillMs.get();
        long elapsed = now - last;
        if (elapsed <= 0) return;
        long add = (long) (elapsed * tokensPerMs);
        if (add <= 0) return; // sub-tick — leave lastRefillMs alone so credit accrues
        if (!lastRefillMs.compareAndSet(last, now)) return; // another thread refilled
        long current = tokens.get();
        long updated = Math.min(capacity, current + add);
        tokens.set(updated);
    }
}
