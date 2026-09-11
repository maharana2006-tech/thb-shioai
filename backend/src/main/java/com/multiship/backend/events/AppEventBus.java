package com.multiship.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    /**
     * Phase 4 — single Redis Stream that carries every event, regardless
     * of topic. Consumers filter by the {@code topic} field embedded in
     * each entry. Single-stream keeps Last-Event-Id resume trivial
     * (one ID space to track) at the cost of every subscriber seeing
     * every event and filtering in-memory. Fine at our scale (tens of
     * events per minute); revisit if event volume grows past ~1k/s.
     *
     * <p>Public so {@link com.multiship.backend.controller.SseController}
     * can subscribe using the same key.
     */
    public static final String STREAM_KEY = "events:all";

    /**
     * Approximate MAXLEN cap for the Redis Stream so it doesn't grow
     * unbounded across an ops shift. Older entries drop off as
     * newer ones arrive. Late-joining SSE clients that resume from
     * a {@code Last-Event-Id} older than the cap simply miss those
     * events — the FE's fallback polling picks up the gap on next
     * refresh.
     *
     * <p>10k = ~2 hr of headroom at 1 event/s sustained, plenty for
     * reconnect windows. Tunable per deployment via
     * {@code app.events.stream-maxlen}.
     */
    public static final long STREAM_MAXLEN = 10_000L;

    /** Field name for the JSON payload within each stream entry. */
    public static final String PAYLOAD_FIELD = "payload";
    /** Field name for the topic within each stream entry — used by
     *  {@link com.multiship.backend.controller.SseController} to filter
     *  events per subscription. */
    public static final String TOPIC_FIELD = "topic";

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
            log.info("AppEventBus ready — publishing to Redis Stream '{}' (approx MAXLEN {}).",
                    STREAM_KEY, STREAM_MAXLEN);
        }
    }

    /**
     * Publish an event to the shared Redis Stream. Never throws —
     * every failure path is caught and logged. Business logic
     * upstream is insulated: if Redis is down or the payload can't be
     * serialised, the caller neither knows nor cares.
     *
     * <p>Each entry carries two fields — {@link #TOPIC_FIELD} for
     * per-subscription filtering and {@link #PAYLOAD_FIELD} for the
     * JSON body. Approximate MAXLEN trimming keeps the stream bounded
     * without exact-length overhead.
     */
    public void publish(AppEvent event) {
        if (event == null) return;
        if (redisTemplate == null) {
            log.debug("AppEventBus no-op (Redis absent): {} {}",
                    event.topic(), event.eventType());
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            Map<String, String> body = Map.of(
                    TOPIC_FIELD, event.topic(),
                    PAYLOAD_FIELD, payload);
            MapRecord<String, String, String> record = StreamRecords.mapBacked(body)
                    .withStreamKey(STREAM_KEY);
            RecordId id = redisTemplate.opsForStream().add(record);
            // Approximate MAXLEN trim on every add — cheap in Redis
            // ("~" arg means "close-enough length, don't scan the whole
            // stream"). Keeps the stream bounded across an ops shift
            // without a background sweeper.
            redisTemplate.opsForStream().trim(STREAM_KEY, STREAM_MAXLEN, true);
            log.debug("AppEventBus published {}.{} → id={} ({} bytes)",
                    event.topic(), event.eventType(), id, payload.length());
        } catch (Exception ex) {
            // Fire-and-forget contract — a Redis blip must not fail
            // the caller. Log WARN so ops can spot chronic issues.
            log.warn("AppEventBus publish failed for {}.{} — event dropped: {}",
                    event.topic(), event.eventType(), ex.getMessage());
        }
    }
}
