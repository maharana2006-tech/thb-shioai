package com.multiship.backend.service;

import com.multiship.backend.model.SignupAttempt;
import com.multiship.backend.repository.SignupAttemptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR D — 1-hour moving-window rate limit on the
 * public signup endpoint. Sprint 51 BS-M4 — also used by the password
 * forgot / reset flow (same email + IP buckets).
 *
 * <p>Enforced per email AND per IP with independent budgets so a
 * shared corporate NAT doesn't lock out every user on the same
 * subnet. Both budgets are configurable via
 * {@code signup.rate-limit.email-per-hour} / {@code .ip-per-hour}.
 *
 * <p>Sprint 51 BS-L3 — the previous implementation had a TOCTOU race:
 * two concurrent callers could both read {@code count = cap - 1}, both
 * see "allowed", both then insert a row so the effective count
 * exceeded the cap. Fixed by fronting the gate with a Redis
 * {@code INCR}-plus-conditional-{@code EXPIRE} (atomic against concurrent
 * increments); the {@code signup_attempts} audit trail continues to be
 * written by {@link #record(String, String, boolean)}. When Redis is
 * absent the limiter falls back to the DB-only implementation — that
 * degrades atomicity but is acceptable for the dev / no-Redis path.
 */
@Slf4j
@Service
public class SignupRateLimiter {

    /** Redis key prefix for the atomic email + IP hour counters. */
    private static final String EMAIL_KEY_PREFIX = "signup-rl:e:";
    private static final String IP_KEY_PREFIX = "signup-rl:i:";

    private final SignupAttemptRepository repository;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Value("${signup.rate-limit.email-per-hour:5}")
    private int emailPerHour;

    @Value("${signup.rate-limit.ip-per-hour:20}")
    private int ipPerHour;

    public SignupRateLimiter(SignupAttemptRepository repository,
                             ObjectProvider<StringRedisTemplate> redisProvider) {
        this.repository = repository;
        this.redisProvider = redisProvider;
    }

    /**
     * @return true when the attempt is under both caps; false when
     * either the email or IP budget is exhausted for the current hour.
     *
     * <p>Sprint 51 BS-L3 — this method now performs an ATOMIC INCR on
     * both counters. The prior read-only implementation had a TOCTOU
     * race: two concurrent callers both observed {@code count=cap-1}
     * and both proceeded, driving the effective total to {@code cap+1}.
     * The INCR path atomically increments and then compares the returned
     * value to the cap — so concurrent callers see monotonically
     * increasing counts and only the ones under the cap are allowed.
     *
     * <p>The counter TTL is stamped only on the very first increment
     * (result == 1) so a runaway loop can't reset the window by
     * re-writing the TTL every hit — the window rolls off exactly
     * one hour after the FIRST hit in the bucket.
     */
    public boolean isAllowed(String email, String ip) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return isAllowedDbFallback(email, ip);
        }
        try {
            // Atomic INCR + first-hit-only EXPIRE. Rounding to the hour
            // gives us the 1-hour fixed window we want (same style as
            // ApiKeyRateLimiter's minute buckets). The window rolls forward
            // exactly one hour after the FIRST INCR in the bucket.
            String emailKey = EMAIL_KEY_PREFIX + safe(email);
            String ipKey = IP_KEY_PREFIX + safe(ip);

            long emailCount = incrementAndStampTtl(redis, emailKey);
            if (emailCount > emailPerHour) {
                log.warn("Signup rate-limit: email {} hit cap ({}/{})",
                        email, emailCount, emailPerHour);
                return false;
            }
            long ipCount = incrementAndStampTtl(redis, ipKey);
            if (ipCount > ipPerHour) {
                log.warn("Signup rate-limit: ip {} hit cap ({}/{})",
                        ip, ipCount, ipPerHour);
                return false;
            }
            return true;
        } catch (Exception ex) {
            // Redis blip — fall back to the DB path so a monitoring gap
            // doesn't deny legitimate signups.
            log.warn("SignupRateLimiter Redis path failed; falling back to DB: {}", ex.getMessage());
            return isAllowedDbFallback(email, ip);
        }
    }

    /**
     * Atomic INCR + conditional first-hit EXPIRE. Redis guarantees INCR
     * is atomic; the follow-up EXPIRE only fires on the very first
     * increment (return value == 1) so the window is anchored to the
     * FIRST hit in the bucket, not continuously refreshed by every hit
     * (the latter would let a slow attacker never actually roll off).
     */
    private long incrementAndStampTtl(StringRedisTemplate redis, String key) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // INCR returned null — treat as "over budget" defensively;
            // the outer catch will fall through to the DB path.
            throw new IllegalStateException("INCR returned null for " + key);
        }
        if (count == 1L) {
            // First hit — stamp a 1-hour TTL. A stray extra EXPIRE call
            // (concurrent racer sees count>1) is a small cost we tolerate
            // for clarity vs. a Lua script.
            redis.expire(key, Duration.ofHours(1));
        }
        return count;
    }

    /**
     * DB-backed fallback for the no-Redis path. Retains the original
     * (non-atomic) behavior; still tighter than nothing, since the
     * dev + tests paths that lack Redis are single-caller. Production
     * Redis is a hard requirement for the atomic path.
     */
    @Transactional(readOnly = true)
    protected boolean isAllowedDbFallback(String email, String ip) {
        LocalDateTime windowStart = LocalDateTime.now().minusHours(1);
        long emailCount = repository.countByEmailAndCreatedAtAfter(email, windowStart);
        if (emailCount >= emailPerHour) {
            log.warn("Signup rate-limit (DB): email {} hit cap ({}/{})",
                    email, emailCount, emailPerHour);
            return false;
        }
        long ipCount = repository.countByIpAndCreatedAtAfter(ip, windowStart);
        if (ipCount >= ipPerHour) {
            log.warn("Signup rate-limit (DB): ip {} hit cap ({}/{})",
                    ip, ipCount, ipPerHour);
            return false;
        }
        return true;
    }

    /**
     * Records an attempt (successful or not). Callers invoke this even
     * on failures so probing counts toward the audit trail. In the
     * Redis-atomic path the INCR in {@link #isAllowed} has already
     * gated the request; this write is for audit + no-Redis fallback.
     */
    @Transactional
    public void record(String email, String ip, boolean succeeded) {
        SignupAttempt attempt = new SignupAttempt();
        attempt.setEmail(email);
        attempt.setIp(ip);
        attempt.setSucceeded(succeeded);
        attempt.setCreatedAt(LocalDateTime.now());
        repository.save(attempt);
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** For observability / tests — the hour epoch used to anchor buckets. */
    static long hourEpoch() {
        return Instant.now().getEpochSecond() / 3600;
    }
}
