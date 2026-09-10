package com.multiship.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Application-wide event publisher. Fire-and-forget: publishing NEVER
 * blocks business logic or throws — a Redis outage must not kill a
 * bulk-label job that has already succeeded, it just means the FE
 * misses a real-time notification and falls back to its polling path
 * for that update.
 *
 * <p>Transport is Redis pub/sub in Phase 1. Phase 4 swaps this for
 * Redis Streams so late-joining SSE clients can resume from
 * {@code Last-Event-Id} instead of missing events during reconnect.
 * The interface is deliberately narrow ({@link #publish(AppEvent)})
 * so that migration is a single-file change.
 *
 * <p>Channel naming: {@code events.<topic>} — e.g. {@code events.bulk-labels}.
 * Prefix keeps event channels segregated from other Redis usage
 * (idempotency keys, rate limits) so a wildcard subscription doesn't
 * accidentally pick them up.
 *
 * <p>Redis-absent behaviour: when
 * {@code spring.data.redis.host} is unset (dev environments, unit
 * tests), Spring Boot doesn't create a
 * {@link StringRedisTemplate} bean. {@code @Autowired(required=false)}
 * leaves the field null; {@link #publish(AppEvent)} then short-circuits
 * with a DEBUG log. Publishers stay clean of "is Redis wired?" checks.
 */
@Slf4j
@Component
public class AppEventBus {

    /** Redis channel prefix — all app events land under {@code events.<topic>}. */
    static final String CHANNEL_PREFIX = "events.";

    private final ObjectMapper objectMapper;

    /**
     * Optional — null when Redis isn't configured (dev / unit tests).
     * When null, {@link #publish(AppEvent)} is a no-op DEBUG log.
     */
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public AppEventBus(ObjectMapper objectMapper,
                       @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        if (redisTemplate == null) {
            log.info("AppEventBus starting in NO-OP mode — Redis not configured. "
                    + "SSE clients will fall back to polling. "
                    + "Set REDIS_HOST to enable real-time event delivery.");
        } else {
            log.info("AppEventBus ready — publishing to Redis pub/sub under '{}<topic>'.",
                    CHANNEL_PREFIX);
        }
    }

    /**
     * Publish an event to its topic channel. Never throws — every
     * failure path is caught and logged. Business logic upstream is
     * insulated: if Redis is down or the payload can't be serialised,
     * the caller neither knows nor cares.
     */
    public void publish(AppEvent event) {
        if (event == null) return;
        if (redisTemplate == null) {
            log.debug("AppEventBus no-op (Redis absent): {} {}",
                    event.topic(), event.eventType());
            return;
        }
        try {
            String channel = CHANNEL_PREFIX + event.topic();
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(channel, payload);
            log.debug("AppEventBus published {}.{} to {}: {} bytes",
                    event.topic(), event.eventType(), channel, payload.length());
        } catch (Exception ex) {
            // Fire-and-forget contract — a Redis blip must not fail
            // the caller. Log WARN so ops can spot chronic issues.
            log.warn("AppEventBus publish failed for {}.{} — event dropped: {}",
                    event.topic(), event.eventType(), ex.getMessage());
        }
    }

    /**
     * Package-private convenience for tests that want to assert the
     * exact channel name a given topic would land on without
     * poking at the constant.
     */
    static String channelFor(String topic) {
        return CHANNEL_PREFIX + topic;
    }
}
