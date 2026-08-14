package com.multiship.backend.service.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
public class IdempotencyService {

    /** Standard TTL for cached idempotent responses. Matches Stripe-style
     *  API idempotency semantics; long enough for network-partition retries,
     *  short enough that Redis memory doesn't blow up under load. */
    private static final Duration TTL = Duration.ofHours(24);

    /** TTL on the pending marker. Kept short so a crashed/never-completed
     *  first request can't lock the key for the full 24h. Sprint 50 PR K
     *  raised this from 60s to 120s: with 60s, a winner crash between
     *  SETNX and SET-main gave duplicate-charge risk once the marker
     *  expired (retry #2 after 60s+ becomes the new winner and re-runs
     *  the handler). 120s matches most gateway/client HTTP timeouts, so
     *  a real crashed winner is beyond ANY reasonable retry window and
     *  the app has visibility to alert on the stuck marker via metrics. */
    private static final Duration PENDING_TTL = Duration.ofSeconds(120);

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
    /** Sprint 51 BP-M4 — optional; production always has Actuator's registry. */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyService(ObjectProvider<StringRedisTemplate> redisProvider,
                              ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.redisProvider = redisProvider;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /** Sprint 51 BP-M4 — back-compat overload for tests. */
    public IdempotencyService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this(redisProvider, null);
    }

    /** Sprint 51 BP-M4 — record a counter increment for one outcome. Silent
     *  when no registry is wired (unit tests / minimal boot). Kept private
     *  so the tag vocabulary stays consistent. */
    private void countOutcome(String outcome) {
        MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry == null) return;
        Counter.builder("idempotency.request")
                .tag("outcome", outcome)
                .description("Idempotency-Key request outcomes: hit (replayed cache), miss (first request), in_progress (pending marker held), disabled (no key / no Redis).")
                .register(registry)
                .increment();
    }
    /** Sprint 50 PR K — private, purpose-built ObjectMapper with the
     *  JavaTimeModule guaranteed registered. Prior code autowired
     *  Spring Boot's shared mapper and tried to register the module on
     *  it lazily at @PostConstruct time; that gave the illusion of
     *  safety but broke in two ways: (a) if a starter mutated the shared
     *  mapper AFTER our @PostConstruct, our module could be dropped;
     *  (b) if some code path bypassed the shared mapper (autoconfig
     *  order-of-init edge case), our safety net never fired. A private
     *  mapper is 40 bytes and always right. */
    private static final ObjectMapper INTERNAL_MAPPER = buildInternalMapper();

    private static ObjectMapper buildInternalMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    /** Sprint 50 PR M — L2: truncate the idempotency key before logging so
     *  a client that stuffs PII / auth into the header (against convention
     *  but possible) doesn't leak the full value into ops logs. 40 chars
     *  is enough to correlate a support ticket without exposing arbitrary
     *  suffix contents. */
    private static String truncKey(String key) {
        if (key == null) return "null";
        return key.length() <= 40 ? key : key.substring(0, 40) + "…";
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
     * Sprint 51 R2 — session-caller overload. Same semantics as the Long
     * apiKeyId variant, but namespaced by a String caller id so
     * session-authenticated endpoints (manual-label, multi-warehouse-label)
     * can participate in idempotent replay. Convention: pass
     * {@code "user:" + username} so namespaces stay disjoint from the
     * API-key path (which uses numeric strings).
     */
    public <T> ResponseEntity<T> executeOrReplay(
            String callerId,
            String idempotencyKey,
            TypeReference<T> typeRef,
            Supplier<ResponseEntity<T>> handler,
            boolean failClosedOnRedisError) {
        return executeOrReplayInternal(callerId, idempotencyKey, typeRef, handler, failClosedOnRedisError);
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
    public <T> ResponseEntity<T> executeOrReplay(
            Long apiKeyId,
            String idempotencyKey,
            TypeReference<T> typeRef,
            Supplier<ResponseEntity<T>> handler,
            boolean failClosedOnRedisError) {
        // Sprint 51 R2 — delegate to the String-callerId internal impl.
        // Namespacing note: API-key callers pass "1", "2", ... (numeric);
        // session callers pass "user:alice" — disjoint by construction.
        return executeOrReplayInternal(
                apiKeyId == null ? null : String.valueOf(apiKeyId),
                idempotencyKey, typeRef, handler, failClosedOnRedisError);
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> executeOrReplayInternal(
            String callerId,
            String idempotencyKey,
            TypeReference<T> typeRef,
            Supplier<ResponseEntity<T>> handler,
            boolean failClosedOnRedisError) {

        // No key supplied → not idempotent; pass through unchanged.
        if (idempotencyKey == null || idempotencyKey.isBlank() || callerId == null || callerId.isBlank()) {
            countOutcome("disabled");
            return handler.get();
        }

        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // No Redis wired — degrade gracefully. Callers still get the
            // fresh response; the fix's guarantee just doesn't fire.
            // We never fail-closed on "Redis not configured" — that's an
            // env-level decision, not a runtime outage.
            countOutcome("disabled");
            return handler.get();
        }

        String trimmed = idempotencyKey.trim();
        String mainKey = KEY_PREFIX + callerId + ":" + trimmed;
        String pendingKey = mainKey + PENDING_SUFFIX;

        // Step 1: try to atomically claim the pending marker. TRUE → we're
        // the first request; FALSE → someone else is in-flight or done.
        Boolean claimed;
        try {
            claimed = redis.opsForValue().setIfAbsent(pendingKey, "1", PENDING_TTL);
        } catch (Exception ex) {
            log.warn("Idempotency SETNX failed for caller={} key={}: {}",
                    callerId, truncKey(idempotencyKey), ex.getMessage());
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
                    countOutcome("hit");
                    return replay(cached, typeRef, idempotencyKey, callerId);
                }
            } catch (Exception ex) {
                log.warn("Idempotency defensive GET failed for caller={} key={}: {}",
                        callerId, truncKey(idempotencyKey), ex.getMessage());
                if (failClosedOnRedisError) {
                    return (ResponseEntity<T>) redisUnavailableResponse();
                }
                // else: fall through — we hold the pending claim, safe to run.
            }

            // First-request execution path.
            countOutcome("miss");
            ResponseEntity<T> fresh = handler.get();

            // Best-effort store. Failure to persist doesn't undo the
            // successful handler; the caller sees the fresh response.
            try {
                // HttpHeaders in Spring Boot 3+/6+ is a MultiValueMap adapter and
                // doesn't implement Map<String, List<String>> directly — copy entries
                // via forEach so Jackson can serialize the plain map.
                Map<String, List<String>> headers = new LinkedHashMap<>();
                fresh.getHeaders().forEach(headers::put);
                String bodyJson = INTERNAL_MAPPER.writeValueAsString(fresh.getBody());
                int status = fresh.getStatusCode().value();
                String stored = INTERNAL_MAPPER.writeValueAsString(
                        new CachedResponse(status, bodyJson, headers));
                redis.opsForValue().set(mainKey, stored, TTL);
            } catch (JsonProcessingException ex) {
                log.warn("Idempotency store serialisation failed for caller={} key={}: {}",
                        callerId, truncKey(idempotencyKey), ex.getMessage());
            } catch (Exception ex) {
                log.warn("Idempotency store write failed for caller={} key={}: {}",
                        callerId, truncKey(idempotencyKey), ex.getMessage());
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
            log.warn("Idempotency replay probe failed for caller={} key={}: {}",
                    callerId, truncKey(idempotencyKey), ex.getMessage());
            if (failClosedOnRedisError) {
                return (ResponseEntity<T>) redisUnavailableResponse();
            }
            return handler.get();
        }

        if (cached != null) {
            countOutcome("hit");
            return replay(cached, typeRef, idempotencyKey, callerId);
        }

        // Still in flight → tell the client to back off.
        countOutcome("in_progress");
        return (ResponseEntity<T>) inProgressResponse();
    }

    /** Deserialize a cached entry and rebuild the {@link ResponseEntity}
     *  including the original response headers. Replay-specific headers
     *  ({@code X-Idempotent-Replay}, {@code Idempotency-Key}) are set
     *  LAST so they're guaranteed to survive. */
    private <T> ResponseEntity<T> replay(String cached, TypeReference<T> typeRef,
                                         String idempotencyKey, String callerId) {
        try {
            CachedResponse hit = INTERNAL_MAPPER.readValue(cached, CachedResponse.class);
            T body = INTERNAL_MAPPER.readValue(hit.bodyJson(), typeRef);
            log.info("Idempotent replay for caller={} key={} → status={}",
                    callerId, truncKey(idempotencyKey), hit.status());

            HttpHeaders headers = new HttpHeaders();
            if (hit.headers() != null) {
                hit.headers().forEach((name, values) -> {
                    // Sprint 50 PR M — L5: don't replay length/hop-by-hop
                    // headers. Cached Content-Length/Content-MD5 can be
                    // wrong for the freshly-serialised body (Jackson field
                    // ordering, mapper differences) and Servlet writes its
                    // own Content-Length anyway. Transfer-Encoding /
                    // Connection are hop-by-hop and must not be replayed.
                    if (!"content-length".equalsIgnoreCase(name)
                            && !"content-md5".equalsIgnoreCase(name)
                            && !"transfer-encoding".equalsIgnoreCase(name)
                            && !"connection".equalsIgnoreCase(name)) {
                        headers.addAll(name, values);
                    }
                });
            }
            // These must win over any cached values.
            headers.set("X-Idempotent-Replay", "true");
            headers.set("Idempotency-Key", idempotencyKey);

            return ResponseEntity.status(HttpStatus.valueOf(hit.status()))
                    .headers(headers)
                    .body(body);
        } catch (Exception ex) {
            log.warn("Idempotency replay deserialization failed for caller={} key={}: {}",
                    callerId, truncKey(idempotencyKey), ex.getMessage());
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
