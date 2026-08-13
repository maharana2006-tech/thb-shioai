package com.multiship.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sprint 51 T2 finding #5 — JWT revocation primitive.
 *
 * <p>Two complementary mechanisms, both consulted on every request in
 * {@code JwtAuthenticationFilter}:
 *
 * <ol>
 *   <li><b>token_version (DB-backed, Caffeine-cached)</b> — every issued
 *       token carries the user's {@code tv} at issue time. A single UPDATE
 *       to {@code users.token_version} invalidates every outstanding token
 *       for that user (logout-all, admin deactivate, role change). Cache
 *       TTL is short (60s) so a bump propagates within a minute even if
 *       the invalidation broadcast (below) missed the instance.</li>
 *   <li><b>Redis jti blacklist</b> — every issued token carries a
 *       per-token UUID {@code jti}. Logout writes the jti to Redis with
 *       TTL = remaining token life; subsequent requests using that jti
 *       are rejected. Enables per-device logout (leave other sessions
 *       intact) without bumping token_version.</li>
 * </ol>
 *
 * <p>Redis is optional. When {@link StringRedisTemplate} is not wired the
 * blacklist becomes a no-op and only the DB-backed tv check runs. This
 * matches the platform's overall Redis-optional posture (see
 * {@code IdempotencyService}, {@code ApiKeyRateLimiter}).
 *
 * <p>The Caffeine cache is <b>local per instance</b>. When the platform
 * runs multi-instance the 60s TTL is the propagation ceiling — a
 * password rotation on instance A becomes effective on instance B within
 * one minute. If tighter revocation is needed, use the jti blacklist
 * (Redis is shared) or bump token_version + wait for the TTL.
 */
@Slf4j
@Service
public class TokenRevocationService {

    /** Redis key prefix for the jti blacklist. Kept short. */
    private static final String JTI_PREFIX = "revoked-jti:";

    /** Caffeine TTL on the username → token_version lookup. Short so a
     *  logout-all / deactivate on instance A propagates to instance B
     *  within this window. Not the primary correctness bound — that's
     *  the DB write; this is a hot-path perf cache. */
    private static final Duration TV_CACHE_TTL = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** Small enough that the cache never dominates heap; large enough
     *  for a busy platform's active-user set. */
    private final Cache<String, Long> tokenVersionCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(TV_CACHE_TTL)
            .build();

    public TokenRevocationService(UserRepository userRepository,
                                   ObjectProvider<StringRedisTemplate> redisProvider) {
        this.userRepository = userRepository;
        this.redisProvider = redisProvider;
    }

    /**
     * Current token_version for the user. Cached; the cache is invalidated
     * inline whenever {@link #bumpTokenVersion(User)} runs on this instance.
     * Returns 0 for unknown username so a leaked token for a deleted user
     * hits {@code tv=0} vs DB{@code =0} → passes; the deactivated_at check
     * in AuthServiceImpl handles the real "user is gone" case.
     */
    public long currentTokenVersion(String username) {
        if (username == null || username.isBlank()) {
            return 0L;
        }
        Long cached = tokenVersionCache.getIfPresent(username);
        if (cached != null) {
            return cached;
        }
        Optional<User> row = userRepository.findByUsername(username);
        long tv = row.map(User::getTokenVersion).map(v -> v == null ? 0L : v).orElse(0L);
        tokenVersionCache.put(username, tv);
        return tv;
    }

    /**
     * Increment the user's token_version, invalidating every outstanding
     * JWT for that account. Called from logout-all, admin deactivate,
     * admin role change, future password reset. Idempotent w.r.t. the
     * cache: local Caffeine entry is invalidated so the next read pulls
     * the fresh value from the DB.
     */
    @Transactional
    public void bumpTokenVersion(User user) {
        if (user == null || user.getUsername() == null) {
            return;
        }
        long current = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
        user.setTokenVersion(current + 1L);
        userRepository.save(user);
        tokenVersionCache.invalidate(user.getUsername());
    }

    /**
     * Blacklist a single JWT by its jti until the token would have
     * expired naturally. Ideal for logout: invalidates THIS session
     * without touching other devices. TTL avoids Redis memory growth.
     * No-op when Redis is absent or the inputs are missing.
     */
    public void blacklistJti(String jti, Instant tokenExpiresAt) {
        if (jti == null || jti.isBlank() || tokenExpiresAt == null) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        long secondsRemaining = tokenExpiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        if (secondsRemaining <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(JTI_PREFIX + jti, "1", Duration.ofSeconds(secondsRemaining));
        } catch (Exception ex) {
            log.warn("Redis jti blacklist write failed for jti={}: {}", jti, ex.getMessage());
        }
    }

    /**
     * True when the jti has been blacklisted since token issuance.
     * Redis absent → returns false (no-op fast-path). Redis error →
     * fail-open with a WARN: blocking a good request because Redis
     * flapped is worse than briefly missing a revocation (the DB tv
     * check is the correctness safety net).
     */
    public boolean isJtiBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            Boolean has = redis.hasKey(JTI_PREFIX + jti);
            return Boolean.TRUE.equals(has);
        } catch (Exception ex) {
            log.warn("Redis jti blacklist read failed for jti={}: {} — fail-open", jti, ex.getMessage());
            return false;
        }
    }
}
