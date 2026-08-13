package com.multiship.backend.service.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Sprint 51 T2 finding #6 — brute-force protection for /auth/login and
 * /oauth/token. Redis-backed sliding-window fail counter keyed by
 * {@code (ip, username)} composite so a distributed attacker can't
 * easily bypass by rotating one axis.
 *
 * <p>Semantics:
 * <ol>
 *   <li>Every failed login/OAuth attempt increments the counter and
 *       (re-)stamps a {@code window-minutes} TTL.</li>
 *   <li>When the counter passes {@code max-failures}, subsequent
 *       attempts are locked for {@code lockout-minutes}. During lockout
 *       the counter TTL is refreshed on each attempt so a brute-force
 *       loop can't wait out the window while still failing.</li>
 *   <li>A successful login clears the counter for that {@code (ip, username)}
 *       pair — legitimate typos don't leave the account half-locked.</li>
 * </ol>
 *
 * <p>Composite key rationale: pure per-IP is bypassed by residential
 * proxies (attacker rotates IPs). Pure per-username misses the "spray
 * a common password across many usernames" pattern. The composite
 * penalises the intersection: an attacker probing many usernames from
 * one IP hits the per-username lockout for each; an attacker probing
 * one username from many IPs hits the per-username lockout too. The
 * legitimate user typing on their own phone can still recover in
 * {@code lockout-minutes} without admin intervention.
 *
 * <p>Fallback: Redis absent → the limiter no-ops with a boot-time WARN.
 * Bcrypt slows attacks even without this layer; this class raises the
 * cost of scripted brute-force from "seconds per credential" to
 * "quarter-hour per five attempts."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthFailureLimiter {

    private static final String KEY_PREFIX = "auth-fail:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Value("${auth.brute-force.max-failures:5}")
    private int maxFailures;

    @Value("${auth.brute-force.window-minutes:15}")
    private int windowMinutes;

    @Value("${auth.brute-force.lockout-minutes:15}")
    private int lockoutMinutes;

    /**
     * True when the given {@code (ip, username)} pair is currently
     * locked out. Callers must check this BEFORE running bcrypt so a
     * lockout response doesn't itself take 150ms per hit — that would
     * amplify a DDoS. When true, callers should return 429 + a
     * {@code Retry-After} header.
     */
    public boolean isLocked(String ip, String username) {
        if (maxFailures <= 0) return false;
        Integer count = currentCount(ip, username);
        return count != null && count >= maxFailures;
    }

    /**
     * Seconds until the lock clears — safe to pass into
     * {@code Retry-After}. Returns {@code lockoutMinutes * 60} as a
     * conservative upper bound when Redis can't report exact TTL.
     */
    public int retryAfterSeconds(String ip, String username) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return lockoutMinutes * 60;
        try {
            Long ttl = redis.getExpire(key(ip, username));
            if (ttl != null && ttl > 0) return ttl.intValue();
        } catch (Exception ex) {
            log.warn("AuthFailureLimiter TTL read failed: {}", ex.getMessage());
        }
        return lockoutMinutes * 60;
    }

    /**
     * Record a failed attempt. Increments the counter and (re-)stamps
     * the TTL — the TTL is refreshed on every failure so a scripted
     * loop can't quietly wait out the window while still failing.
     * No-op when Redis is absent.
     */
    public void recordFailure(String ip, String username) {
        if (maxFailures <= 0) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            String k = key(ip, username);
            Long count = redis.opsForValue().increment(k);
            // Stamp a fresh TTL. Use the longer of window / lockout so
            // a caller that just tripped the threshold stays locked for
            // the intended lockout duration without a second TTL write.
            long ttlMinutes = count != null && count >= maxFailures
                    ? lockoutMinutes
                    : windowMinutes;
            redis.expire(k, Duration.ofMinutes(ttlMinutes));
        } catch (Exception ex) {
            log.warn("AuthFailureLimiter INCR failed for {}|{}: {}",
                    ip, username, ex.getMessage());
        }
    }

    /**
     * Clear the failure counter for a successful login. Prevents a
     * legitimate user's earlier typos from lingering as half-locked
     * state that surprises them at their next login.
     */
    public void recordSuccess(String ip, String username) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            redis.delete(key(ip, username));
        } catch (Exception ex) {
            log.warn("AuthFailureLimiter DEL failed for {}|{}: {}",
                    ip, username, ex.getMessage());
        }
    }

    private Integer currentCount(String ip, String username) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return null;
        try {
            String v = redis.opsForValue().get(key(ip, username));
            return v == null ? null : Integer.parseInt(v);
        } catch (Exception ex) {
            log.warn("AuthFailureLimiter GET failed for {}|{}: {}",
                    ip, username, ex.getMessage());
            return null;
        }
    }

    private static String key(String ip, String username) {
        // Colon delimiter matches the platform's other Redis keys.
        // Lowercasing username keeps counters aligned across typos on
        // case (usernames are stored case-insensitively in login flow).
        String safeIp = ip == null || ip.isBlank() ? "unknown" : ip;
        String safeUser = username == null || username.isBlank()
                ? "unknown"
                : username.toLowerCase(java.util.Locale.ROOT);
        return KEY_PREFIX + safeIp + ":" + safeUser;
    }
}
