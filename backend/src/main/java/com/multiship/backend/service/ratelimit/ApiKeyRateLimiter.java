package com.multiship.backend.service.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Sprint 50 Tier 1 finding #15 — per-API-key rate limiter for the public
 * v2 API. Fixed-window counter in Redis (INCR + EXPIRE) keyed by
 * {@code (apiKeyId, minute-epoch)}; one atomic bump per request, one 60s
 * TTL per bucket. Distributed across app instances because Redis is
 * shared.
 *
 * <p>Trade-offs of fixed-window vs sliding-window / token-bucket:
 * <ul>
 *   <li>Fixed-window is 2 Redis ops (INCR + EXPIRE on first bump) —
 *       cheapest under load.</li>
 *   <li>Edge-effect: an attacker can send {@code limit} requests at
 *       minute:59.999 + another {@code limit} at minute:00.001, so peak
 *       is 2×limit for one second per minute. Acceptable trade for the
 *       simplicity here; a follow-up can drop in a token-bucket
 *       (Bucket4j) if that peak ever matters.</li>
 * </ul>
 *
 * <p>Fallback: when Redis is absent, {@link #tryAcquire} always returns
 * {@link Decision#allowed()} — the limiter degrades to no-op. Tests
 * that don't stand up Redis keep passing; envs without REDIS_HOST just
 * skip the limit.
 *
 * <p>Configuration: {@code rate.limit.api-key.requests-per-minute}
 * (default 600 = 10 req/sec). Zero or negative disables the limiter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyRateLimiter {

    /** Redis key prefix. Short — Redis stores keys in memory. */
    private static final String KEY_PREFIX = "rl:api:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Value("${rate.limit.api-key.requests-per-minute:600}")
    private int requestsPerMinute;

    /**
     * Attempt to consume one request slot for the given API key id.
     *
     * @param apiKeyId caller's API key id (null skips the limit — internal
     *                 wiring, not a public request)
     * @return {@link Decision} carrying the allow flag, current count,
     *         configured limit, and Retry-After hint (0 if allowed)
     */
    public Decision tryAcquire(Long apiKeyId) {
        // Disabled config or unknown caller → allow, count truly known (= 0).
        if (apiKeyId == null || requestsPerMinute <= 0) {
            return Decision.allowed(0, requestsPerMinute);
        }
        // Sprint 50 PR L — Redis absent means we CAN'T count. Signal that
        // to callers so response headers don't lie ("Remaining=full budget"
        // during an outage). Interceptors should omit the header instead
        // of emitting a stale number that integrators pace against.
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Decision.allowedButCountUnknown(requestsPerMinute);
        }

        long now = Instant.now().getEpochSecond();
        long minuteEpoch = now / 60;
        String key = KEY_PREFIX + apiKeyId + ":" + minuteEpoch;

        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                // Redis returned unexpected null; fail-open rather than
                // fail-closed — a monitoring gap should never DoS callers.
                log.warn("Rate limiter INCR returned null for key={}; allowing", key);
                return Decision.allowedButCountUnknown(requestsPerMinute);
            }
            // Set TTL only on the first bump — INCR alone doesn't touch expiry.
            // A stray extra EXPIRE call is a small no-op cost we tolerate for
            // clarity; the alternative (Lua) is a real dependency.
            if (count == 1L) {
                redis.expire(key, Duration.ofSeconds(65));
            }
            if (count > requestsPerMinute) {
                long secondsUntilNextMinute = 60 - (now % 60);
                return Decision.denied(count.intValue(), requestsPerMinute,
                        (int) secondsUntilNextMinute);
            }
            return Decision.allowed(count.intValue(), requestsPerMinute);
        } catch (Exception ex) {
            // Redis blip — fail-open. A monitoring degradation should not
            // deny legitimate traffic; the alert on redis-down is separate.
            // Signal countUnknown so the interceptor doesn't emit a
            // misleading Remaining header.
            log.warn("Rate limiter Redis op failed for apiKey={}: {}",
                    apiKeyId, ex.getMessage());
            return Decision.allowedButCountUnknown(requestsPerMinute);
        }
    }

    /**
     * Immutable result of a rate-limit decision.
     *
     * <p>Sprint 50 PR L — {@code countKnown=false} means the limiter
     * fell through (Redis down, INCR returned null, exception caught).
     * Callers should omit the {@code X-RateLimit-Remaining} response
     * header in that case rather than surface a lying "full budget"
     * value that integrators pace against.
     */
    public record Decision(boolean allowed, int currentCount, int limit,
                           int retryAfterSeconds, boolean countKnown) {
        static Decision allowed(int current, int limit) {
            return new Decision(true, current, limit, 0, true);
        }
        static Decision allowedButCountUnknown(int limit) {
            return new Decision(true, 0, limit, 0, false);
        }
        static Decision denied(int current, int limit, int retry) {
            return new Decision(false, current, limit, retry, true);
        }
    }
}
