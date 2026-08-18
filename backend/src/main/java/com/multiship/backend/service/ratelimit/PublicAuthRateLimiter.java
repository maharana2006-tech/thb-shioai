package com.multiship.backend.service.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Audit R2 (#381 + #384) — per-IP rate limit on the public token-based
 * auth endpoints ({@code GET /auth/invite/{token}},
 * {@code POST /auth/verify-email?token=…}). These endpoints are
 * unauthenticated + take an opaque token in the URL, so they were
 * previously unbounded — an attacker could hammer them to burn CPU
 * on \code{repository.findByToken} lookups without ever succeeding.
 *
 * <p>Tokens themselves are 192-bit SecureRandom → brute force is
 * mathematically infeasible; the guard is DoS-shaped, not credential-
 * compromise-shaped.
 *
 * <p>Modelled on {@link com.multiship.backend.service.SignupRateLimiter}
 * — atomic INCR on Redis with a per-hour fixed window, plus a
 * fail-open fallback for the dev / no-Redis path (public unauth
 * endpoints degrading closed on a Redis blip would lock out real
 * invitees + verifiers).
 */
@Slf4j
@Service
public class PublicAuthRateLimiter {

    private static final String KEY_PREFIX = "pubauth-rl:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** Per-IP cap per hour. Default 60 = one attempt/minute average for a
     *  legitimate one-click flow; comfortably above expected retry patterns. */
    @Value("${public-auth.rate-limit.per-hour:60}")
    private int perHour;

    public PublicAuthRateLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /**
     * @param endpointTag short label like "invite" or "verify-email" so the
     *                    two endpoints get independent buckets (a curious
     *                    invitee hitting the preview 30 times doesn't
     *                    accidentally block the verify path).
     * @return true when the caller is under budget; false when the hour
     *         window is exhausted. Fails open when Redis is unavailable.
     */
    public boolean isAllowed(String endpointTag, String ip) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // No Redis → fail open. Dev + tests don't require Redis and
            // locking out legitimate invitees over a monitoring blip is
            // worse than losing rate limiting on a public read.
            return true;
        }
        try {
            String key = KEY_PREFIX + safe(endpointTag) + ":" + safe(ip);
            Long count = redis.opsForValue().increment(key);
            if (count == null) return true;
            if (count == 1L) {
                // Stamp TTL only on first hit so the window rolls forward
                // exactly one hour after the FIRST attempt in the bucket.
                redis.expire(key, Duration.ofHours(1));
            }
            if (count > perHour) {
                log.warn("PublicAuthRateLimiter: {} from {} hit cap ({}/{})",
                        endpointTag, ip, count, perHour);
                return false;
            }
            return true;
        } catch (Exception ex) {
            // Any Redis blip — fail open. Same reasoning as no-Redis path.
            log.warn("PublicAuthRateLimiter Redis path failed; failing open: {}", ex.getMessage());
            return true;
        }
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
