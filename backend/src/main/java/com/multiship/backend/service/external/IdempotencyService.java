package com.multiship.backend.service.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>Concurrent-first-request race (Tier 1-C blocker):</b> the naive
 * probe→miss→execute→store pattern lets two simultaneous POSTs with the
 * same key both slip into the handler and create duplicates. We now
 * use a SETNX pending-marker (short 60s TTL) to atomically claim the
 * key. The claim winner runs the handler; a claim loser either replays
 * a fully-stored entry or returns {@code 409 CONFLICT} with
 * {@code IDEMPOTENCY_IN_PROGRESS} + {@code Retry-After: 5} so the
 * partner client can retry once the winner's response lands.
 *
 * <p><b>Header replay (Tier 1-C blocker):</b> the cached record now
 * carries the full response headers map so replays return the exact
 * {@code Location} / carrier tracking / etc. that the first request
 * emitted. Replay-specific headers ({@code X-Idempotent-Replay: true}
 * and {@code Idempotency-Key}) are applied AFTER the cached headers so
 * a cached value can never overwrite them.
 *
 * <p><b>Fail-closed mode (Tier 1-C blocker):</b> money-touching
 * endpoints (create shipment, void, schedule pickup, close-out) pass
 * {@code failClosedOnRedisError=true}. When Redis is down the service
 * returns {@code 503 SERVICE_UNAVAILABLE} + {@code IDEMPOTENCY_UNAVAILABLE}
 * instead of falling through — because a fail-open handler run without
 * the dedup guarantee could double-charge. Read-heavy endpoints
 * ({@code /rates}, {@code /rate-shop}) keep the fail-open default:
 * a re-run of a rate quote is safe.
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

    /** TTL on the pending marker. Kept short so a crashed/never-completed
     *  first request can't lock the key for the full 24h. 60s is a
     *  comfortable ceiling for any single handler invocation. */
    private static final Duration PENDING_TTL = Duration.ofSeconds(60);

    /** Retry-After (seconds) surfaced when the service returns 409 or 503
     *  so clients back off before the pending marker or Redis outage
     *  clears. */
    private static final String RETRY_AFTER_SECONDS = "5";

    /** Redis key prefix. Kept short — Redis stores keys in memory. */
    private static final String KEY_PREFIX = "idem:";

    /** Suffix on the SETNX pending-marker key. Distinct from the main
     *  key so the pending TTL never collides with the 24h response TTL. */
    private static final String PENDING_SUFFIX = ":pending";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    /**
     * Sprint 50 Tier 1-C — the autowired ObjectMapper is Spring Boot's
     * default, which should carry JavaTimeModule via
     * spring-boot-starter-jackson. Belt-and-braces register anyway so a
     * caller wiring a bare {@code new ObjectMapper()} in unit tests or a
     * downstream config that overrides the Boot mapper still serialises
     * {@code ApiResponse.timestamp} (LocalDateTime) correctly. Missing
     * the module silently loses the mainKey write (the try/catch logs
     * WARN), which breaks the second-call replay contract.
     */
    @PostConstruct
    void ensureJavaTimeModule() {
        if (objectMapper != null
                && !objectMapper.getRegisteredModuleIds().contains(new JavaTimeModule().getTypeId())) {
            objectMapper.registerModule(new JavaTimeModule());
        }
    }

    /**
     * Fail-open overload — matches the pre-Tier-1-C behavior for
     * endpoints where re-running the handler on Redis failure is safe
     * (e.g. rate quotes). See the fail-closed overload for the
     * money-touching variant.
     */
    public <T> ResponseEntity<T> executeOrReplay(
            Long apiKeyId,
            String idempotencyKey,
            TypeReference<T> typeRef,
            Supplier<ResponseEntity<T>> handler) {
        return executeOrReplay(apiKeyId, idempotencyKey, typeRef, handler, false);
    }

    /**
     * The core primitive. Behaves as one of:
     * <ol>
     *   <li>Redis is absent OR idempotencyKey is null/blank OR apiKeyId is
     *       null → runs the handler, does not cache. Callers still get the
     *       plain response.</li>
     *   <li>Redis is present AND we claim the pending marker via SETNX →
     *       we're the first request. Handler runs; response is cached
     *       under the main key with 24h TTL; caller gets the fresh
     *       response with {@code Idempotency-Key} echoed.</li>
     *   <li>Redis is present AND the pending claim failed AND the main
     *       key has a value → replay the cached response with
     *       {@code X-Idempotent-Replay: true} + full cached headers.
     *       Handler is NOT invoked. This is the whole point of the fix.</li>
     *   <li>Redis is present AND the pending claim failed AND the main
     *       key is empty → another request is still in flight. Return
     *       {@code 409 CONFLICT} + {@code IDEMPOTENCY_IN_PROGRESS} +
     *       {@code Retry-After: 5}.</li>
     *   <li>Redis throws AND {@code failClosedOnRedisError=true} →
     *       {@code 503 SERVICE_UNAVAILABLE} + {@code IDEMPOTENCY_UNAVAILABLE}.
     *       Money-touching endpoints use this to guarantee no duplicate
     *       charges when the store is down.</li>
     *   <li>Redis throws AND {@code failClosedOnRedisError=false} → log
     *       and run the handler. Safe for read-heavy endpoints.</li>
     * </ol>
     *
     * @param apiKeyId caller's API key id (must non-null to participate)
     * @param idempotencyKey the {@code Idempotency-Key} header value
     * @param typeRef Jackson type token for deserializing a cached body
     *                back into the response type
     * @param handler produces the fresh response on cache miss
     * @param failClosedOnRedisError if true, Redis errors surface as
     *                               503 with {@code IDEMPOTENCY_UNAVAILABLE}
     *                               instead of falling through to the handler
     */
    @SuppressWarnings("unchecked")
    public <T> ResponseEntity<T> executeOrReplay(
            Long apiKeyId,
            String idempotencyKey,
            TypeReference<T> typeRef,
            Supplier<ResponseEntity<T>> handler,
            boolean failClosedOnRedisError) {

        // No key supplied → not idempotent; pass through unchanged.
        if (idempotencyKey == null || idempotencyKey.isBlank() || apiKeyId == null) {
            return handler.get();
        }

        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // No Redis wired — degrade gracefully. Callers still get the
            // fresh response; the fix's guarantee just doesn't fire.
            // We never fail-closed on "Redis not configured" — that's an
            // env-level decision, not a runtime outage.
            return handler.get();
        }

        String trimmed = idempotencyKey.trim();
        String mainKey = KEY_PREFIX + apiKeyId + ":" + trimmed;
        String pendingKey = mainKey + PENDING_SUFFIX;

        // Step 1: try to atomically claim the pending marker. TRUE → we're
        // the first request; FALSE → someone else is in-flight or done.
        Boolean claimed;
        try {
            claimed = redis.opsForValue().setIfAbsent(pendingKey, "1", PENDING_TTL);
        } catch (Exception ex) {
            log.warn("Idempotency SETNX failed for apiKey={} key={}: {}",
                    apiKeyId, idempotencyKey, ex.getMessage());
            if (failClosedOnRedisError) {
                return (ResponseEntity<T>) redisUnavailableResponse();
            }
            return handler.get();
        }

        if (Boolean.TRUE.equals(claimed)) {
            // Winner path — but do a defensive GET on the main key in case
            // a previous request completed and its pending marker had
            // already expired between two racing retries. Cheap and rare.
            try {
                String cached = redis.opsForValue().get(mainKey);
                if (cached != null) {
                    return replay(cached, typeRef, idempotencyKey, apiKeyId);
                }
            } catch (Exception ex) {
                log.warn("Idempotency defensive GET failed for apiKey={} key={}: {}",
                        apiKeyId, idempotencyKey, ex.getMessage());
                if (failClosedOnRedisError) {
                    return (ResponseEntity<T>) redisUnavailableResponse();
                }
                // else: fall through — we hold the pending claim, safe to run.
            }

            // First-request execution path.
            ResponseEntity<T> fresh = handler.get();

            // Best-effort store. Failure to persist doesn't undo the
            // successful handler; the caller sees the fresh response.
            try {
                // HttpHeaders in Spring Boot 3+/6+ is a MultiValueMap adapter and
                // doesn't implement Map<String, List<String>> directly — copy entries
                // via forEach so Jackson can serialize the plain map.
                Map<String, List<String>> headers = new LinkedHashMap<>();
                fresh.getHeaders().forEach(headers::put);
                String bodyJson = objectMapper.writeValueAsString(fresh.getBody());
                int status = fresh.getStatusCode().value();
                String stored = objectMapper.writeValueAsString(
                        new CachedResponse(status, bodyJson, headers));
                redis.opsForValue().set(mainKey, stored, TTL);
            } catch (JsonProcessingException ex) {
                log.warn("Idempotency store serialisation failed for apiKey={} key={}: {}",
                        apiKeyId, idempotencyKey, ex.getMessage());
            } catch (Exception ex) {
                log.warn("Idempotency store write failed for apiKey={} key={}: {}",
                        apiKeyId, idempotencyKey, ex.getMessage());
            }

            // Return the fresh response with Idempotency-Key echoed.
            // Cached headers can't overwrite this because we're on the
            // first path — fresh.getHeaders() are the real handler's headers.
            return ResponseEntity.status(fresh.getStatusCode())
                    .headers(h -> {
                        h.putAll(fresh.getHeaders());
                        h.set("Idempotency-Key", idempotencyKey);
                    })
                    .body(fresh.getBody());
        }

        // Claim FAILED — someone else is in flight OR already done.
        String cached;
        try {
            cached = redis.opsForValue().get(mainKey);
        } catch (Exception ex) {
            log.warn("Idempotency replay probe failed for apiKey={} key={}: {}",
                    apiKeyId, idempotencyKey, ex.getMessage());
            if (failClosedOnRedisError) {
                return (ResponseEntity<T>) redisUnavailableResponse();
            }
            return handler.get();
        }

        if (cached != null) {
            return replay(cached, typeRef, idempotencyKey, apiKeyId);
        }

        // Still in flight → tell the client to back off.
        return (ResponseEntity<T>) inProgressResponse();
    }

    /** Deserialize a cached entry and rebuild the {@link ResponseEntity}
     *  including the original response headers. Replay-specific headers
     *  ({@code X-Idempotent-Replay}, {@code Idempotency-Key}) are set
     *  LAST so they're guaranteed to survive. */
    private <T> ResponseEntity<T> replay(String cached, TypeReference<T> typeRef,
                                         String idempotencyKey, Long apiKeyId) {
        try {
            CachedResponse hit = objectMapper.readValue(cached, CachedResponse.class);
            T body = objectMapper.readValue(hit.bodyJson(), typeRef);
            log.info("Idempotent replay for apiKey={} key={} → status={}",
                    apiKeyId, idempotencyKey, hit.status());

            HttpHeaders headers = new HttpHeaders();
            if (hit.headers() != null) {
                hit.headers().forEach(headers::addAll);
            }
            // These must win over any cached values.
            headers.set("X-Idempotent-Replay", "true");
            headers.set("Idempotency-Key", idempotencyKey);

            return ResponseEntity.status(HttpStatus.valueOf(hit.status()))
                    .headers(headers)
                    .body(body);
        } catch (Exception ex) {
            log.warn("Idempotency replay deserialization failed for apiKey={} key={}: {}",
                    apiKeyId, idempotencyKey, ex.getMessage());
            // Corrupt cache entry — surface as in-progress so the caller
            // retries rather than getting a silently different response.
            @SuppressWarnings("unchecked")
            ResponseEntity<T> resp = (ResponseEntity<T>) inProgressResponse();
            return resp;
        }
    }

    /** 409 with {@code IDEMPOTENCY_IN_PROGRESS} — another request holds
     *  the pending marker but hasn't stored a response yet. */
    private ResponseEntity<ApiResponse<Object>> inProgressResponse() {
        ApiResponse<Object> body = ApiResponse.builder()
                .status("ERROR")
                .code(HttpStatus.CONFLICT.value())
                .timestamp(LocalDateTime.now())
                .message("A request with this Idempotency-Key is already being processed. Retry in a few seconds.")
                .errorCode(ErrorCode.IDEMPOTENCY_IN_PROGRESS.name())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body);
    }

    /** 503 with {@code IDEMPOTENCY_UNAVAILABLE} — fail-closed response
     *  for money-touching endpoints when the Redis store is down. */
    private ResponseEntity<ApiResponse<Object>> redisUnavailableResponse() {
        ApiResponse<Object> body = ApiResponse.builder()
                .status("ERROR")
                .code(HttpStatus.SERVICE_UNAVAILABLE.value())
                .timestamp(LocalDateTime.now())
                .message("The idempotency store is unavailable. Retry after the Retry-After interval.")
                .errorCode(ErrorCode.IDEMPOTENCY_UNAVAILABLE.name())
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body);
    }

    /** Serialised cache entry: HTTP status + JSON body + full headers map.
     *  {@code @JsonIgnoreProperties(ignoreUnknown=true)} keeps forward
     *  compatibility if we add fields later; the null-safe constructor
     *  keeps backward compatibility with entries stored before the
     *  headers field was added. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CachedResponse(int status, String bodyJson, Map<String, List<String>> headers) {
        CachedResponse {
            headers = headers == null ? Map.of() : headers;
        }
    }
}
