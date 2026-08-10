package com.multiship.backend.service.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Sprint 50 Tier 1 finding #7 — Idempotency-Key replay store for the
 * public v2 API. Before this landed the header was echoed on the
 * response but never persisted; a duplicate POST created two orders +
 * two charges. Now the (apiKeyId, key) tuple maps to the first response
 * status + body for 24h; replayed requests short-circuit to that entry.
 *
 * <p><b>Namespacing:</b> keys are scoped by API key id so a partner's
 * chosen idempotency key can never collide with another partner's.
 *
 * <p><b>TTL:</b> 24h. RFC-standard for HTTP idempotency semantics — long
 * enough that a client retry across a network partition still hits the
 * cache, short enough that Redis memory stays bounded.
 *
 * <p><b>Fallback:</b> when no Redis connection is wired (unit tests,
 * envs without REDIS_HOST), the service no-ops — every call just
 * executes the handler. Callers do NOT have to check whether Redis is
 * present; the ObjectProvider absorbs that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    /** Standard TTL for cached idempotent responses. Matches Stripe-style
     *  API idempotency semantics; long enough for network-partition retries,
     *  short enough that Redis memory doesn't blow up under load. */
    private static final Duration TTL = Duration.ofHours(24);

    /** Redis key prefix. Kept short — Redis stores keys in memory. */
    private static final String KEY_PREFIX = "idem:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    /**
     * The core primitive. Behaves as one of:
     * <ol>
     *   <li>Redis is absent OR idempotencyKey is null/blank OR apiKeyId is
     *       null → runs the handler, does not cache. Callers still get the
     *       plain response.</li>
     *   <li>Redis is present AND (apiKeyId, key) has a cached entry →
     *       returns the cached response with the {@code X-Idempotent-Replay: true}
     *       header. The handler is NOT invoked. This is the whole point of
     *       the fix — a duplicate POST from a partner retry no longer
     *       creates a second shipment.</li>
     *   <li>Redis is present AND cache miss → runs the handler, stores the
     *       response with the 24h TTL, echoes the Idempotency-Key header
     *       on the response for caller trace.</li>
     * </ol>
     *
     * @param apiKeyId caller's API key id (must non-null to participate)
     * @param idempotencyKey the {@code Idempotency-Key} header value
     * @param typeRef Jackson type token for deserializing a cached body
     *                back into the response type
     * @param handler produces the fresh response on cache miss
     */
    public <T> ResponseEntity<T> executeOrReplay(
            Long apiKeyId,
            String idempotencyKey,
            TypeReference<T> typeRef,
            Supplier<ResponseEntity<T>> handler) {

        // No key supplied → not idempotent; pass through unchanged.
        if (idempotencyKey == null || idempotencyKey.isBlank() || apiKeyId == null) {
            return handler.get();
        }

        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // No Redis wired — degrade gracefully. Callers still get the
            // fresh response; the fix's guarantee just doesn't fire.
            return handler.get();
        }

        String redisKey = KEY_PREFIX + apiKeyId + ":" + idempotencyKey.trim();

        // Cache probe first.
        try {
            String cached = redis.opsForValue().get(redisKey);
            if (cached != null) {
                CachedResponse hit = objectMapper.readValue(cached, CachedResponse.class);
                T body = objectMapper.readValue(hit.bodyJson(), typeRef);
                log.info("Idempotent replay for apiKey={} key={} → status={}",
                        apiKeyId, idempotencyKey, hit.status());
                return ResponseEntity.status(HttpStatus.valueOf(hit.status()))
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Idempotent-Replay", "true")
                        .body(body);
            }
        } catch (Exception ex) {
            // Redis or deserialization failed — do NOT block the request.
            // Log and fall through to the handler; worst case we allow one
            // extra execution rather than 500 the caller.
            log.warn("Idempotency lookup failed for apiKey={} key={}: {}",
                    apiKeyId, idempotencyKey, ex.getMessage());
        }

        // Cache miss (or Redis probe threw) — run the handler.
        ResponseEntity<T> fresh = handler.get();

        // Best-effort store. A failure to persist doesn't undo the successful
        // handler; the caller sees the fresh response, and their next retry
        // simply runs the handler again (silent-fallback consequence is the
        // pre-fix behaviour, which we're improving upon anyway).
        try {
            String bodyJson = objectMapper.writeValueAsString(fresh.getBody());
            int status = fresh.getStatusCode().value();
            String stored = objectMapper.writeValueAsString(new CachedResponse(status, bodyJson));
            redis.opsForValue().set(redisKey, stored, TTL);
        } catch (JsonProcessingException ex) {
            log.warn("Idempotency store serialisation failed for apiKey={} key={}: {}",
                    apiKeyId, idempotencyKey, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Idempotency store write failed for apiKey={} key={}: {}",
                    apiKeyId, idempotencyKey, ex.getMessage());
        }

        return ResponseEntity.status(fresh.getStatusCode())
                .headers(h -> {
                    h.putAll(fresh.getHeaders());
                    h.set("Idempotency-Key", idempotencyKey);
                })
                .body(fresh.getBody());
    }

    /** Serialised cache entry: HTTP status + the JSON body as a string. */
    record CachedResponse(int status, String bodyJson) {}
}
