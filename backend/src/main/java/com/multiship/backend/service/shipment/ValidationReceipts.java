package com.multiship.backend.service.shipment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 — remembers which payload fingerprints passed a dry run, for a
 * bounded time, so a purchase with a matching fingerprint can skip the
 * carrier rates/validate hop it would otherwise repeat. Redis when it is
 * available (shared across nodes), else an in-memory map with the same TTL.
 * ponytail: the in-memory fallback is per node; a mismatch only costs the
 * carrier hop we run today anyway.
 */
@Component
public class ValidationReceipts {

    public static final Duration TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "shipment:validated:";

    private final ObjectProvider<StringRedisTemplate> redis;
    private final Map<String, Instant> local = new ConcurrentHashMap<>();

    public ValidationReceipts(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    /** Record a passing dry run; returns when it expires. */
    public Instant remember(String fingerprint) {
        Instant until = Instant.now().plus(TTL);
        StringRedisTemplate r = redis.getIfAvailable();
        if (r != null) {
            try {
                r.opsForValue().set(PREFIX + fingerprint, "1", TTL);
                return until;
            } catch (RuntimeException ignored) {
                // Redis down: fall through to the local map.
            }
        }
        local.put(fingerprint, until);
        if (local.size() > 10_000) local.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));
        return until;
    }

    public boolean isFresh(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return false;
        StringRedisTemplate r = redis.getIfAvailable();
        if (r != null) {
            try {
                if (Boolean.TRUE.equals(r.hasKey(PREFIX + fingerprint))) return true;
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        Instant until = local.get(fingerprint);
        return until != null && until.isAfter(Instant.now());
    }
}
