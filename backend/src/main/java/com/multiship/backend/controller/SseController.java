package com.multiship.backend.controller;

import com.multiship.backend.events.AppEventBus;
import com.multiship.backend.service.TenantScopeEnforcer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Server-Sent Events endpoint for real-time FE updates. Replaces the
 * 2-4s polling loops on BulkLabelModal and DataHistoryPage with a
 * single long-lived HTTP connection per operator that receives push
 * updates as backend state changes.
 *
 * <p>Wire format is standard SSE (see RFC 8895 / html.spec.whatwg.org
 * /multipage/server-sent-events.html): each event is
 * {@code id: <n>\nevent: <type>\ndata: <json>\n\n}. Browsers speak
 * this natively via {@code EventSource}.
 *
 * <p>Endpoint: {@code GET /api/v1/events/stream?topics=bulk-labels,import-batches}.
 * The {@code topics} query parameter narrows what the client wants
 * (missing = all topics). Server-side tenant filter drops events for
 * OTHER tenants before they hit the wire so a scoped USER never sees
 * another tenant's activity.
 *
 * <p>Backpressure: {@link SseEmitter} has an internal queue; if the
 * client is slow the emitter times out and the subscription is torn
 * down. Clients auto-reconnect (browser default) — the FE fallback
 * polling picks up any events dropped during reconnect (Phase 4 will
 * add Redis Streams for durability).
 *
 * <p>Redis-absent behaviour: this controller is not registered when
 * {@link RedisMessageListenerContainer} isn't wired
 * ({@code REDIS_HOST} unset). The FE's fetch to
 * {@code /events/stream} then 404s, which its EventSource
 * error-handler recognises as "not available" and falls back to
 * polling.
 */
@Slf4j
@Tag(name = "Server-Sent Events", description = "Real-time backend→FE push channel over SSE")
@Controller
@RequestMapping("/api/v1/events")
public class SseController {

    /** Read-timeout for the SSE emitter. 30 min balances "don't drop
     *  idle operators too aggressively" against "don't hold sockets
     *  forever if the tab was closed". Browsers auto-reconnect on
     *  timeout so an active operator seamlessly rejoins. */
    private static final long SSE_TIMEOUT_MS = 30L * 60_000L;

    /** Topics we recognise. Anything else in the query string is
     *  ignored (not an error — future compatibility). */
    private static final Set<String> KNOWN_TOPICS = Set.of("bulk-labels", "import-batches");

    /** Auth check + tenant lookup, injected via constructor. */
    private final TenantScopeEnforcer tenantScope;

    /** Optional — null when Redis is disabled (dev/unit-test mode).
     *  Controller endpoint returns 503 in that case so the FE knows
     *  to fall back to polling. */
    private final RedisMessageListenerContainer redisListeners;

    /** Monotonic subscription-scoped event id. Client's Last-Event-Id
     *  handling in Phase 4 will make this durable; for now it's just
     *  a per-connection counter for browser reconnect logic. */
    private final AtomicLong nextId = new AtomicLong(1);

    /** Live subscriptions — used to log presence + shut down cleanly. */
    private final ConcurrentHashMap<Long, SseEmitter> live = new ConcurrentHashMap<>();

    @Autowired
    public SseController(TenantScopeEnforcer tenantScope,
                        @Autowired(required = false) RedisMessageListenerContainer redisListeners) {
        this.tenantScope = tenantScope;
        this.redisListeners = redisListeners;
        if (redisListeners == null) {
            log.info("SseController: Redis not configured — /events/stream will return 503; "
                    + "FE falls back to polling.");
        }
    }

    /**
     * Open an SSE subscription. Long-lived HTTP response — the
     * connection stays open until the client disconnects or the
     * SSE_TIMEOUT_MS timer fires (browser then reconnects).
     */
    @Operation(summary = "Real-time backend event stream (SSE)",
            description = "Opens a long-lived text/event-stream connection. Server pushes typed events "
                    + "as backend state changes (bulk-label job status, import batch status). Filter by "
                    + "comma-separated topics query parameter; omit to receive all. Tenant-scoped: only "
                    + "events for the caller's tenant are delivered.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "topics", required = false) String topicsCsv) {
        // Redis-absent: fail early with a well-known error so the FE
        // EventSource's onerror falls back to polling immediately.
        if (redisListeners == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.completeWithError(new IllegalStateException(
                    "SSE unavailable — Redis is not configured. Set REDIS_HOST to enable."));
            return emitter;
        }

        Set<String> topics = parseTopics(topicsCsv);
        String tenant = tenantScope.resolveScope().orElse(null);
        boolean platform = tenantScope.isPlatformOperator();
        long subscriptionId = nextId.getAndIncrement();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Redis subscriber — one MessageListener per SSE subscription.
        // The listener stays registered until the emitter completes;
        // the completion callbacks below detach it. Pattern topic
        // matches "events.*" so we get every event type; we filter
        // in-memory to keep the subscription set small.
        MessageListener listener = new SseRelayListener(emitter, topics, tenant, platform, subscriptionId);
        PatternTopic pattern = PatternTopic.of("events.*");
        redisListeners.addMessageListener(listener, pattern);
        live.put(subscriptionId, emitter);
        log.info("SSE subscription {} opened (topics={}, tenant={}, platform={})",
                subscriptionId, topics, tenant, platform);

        // Detach the Redis listener when the emitter finishes for ANY
        // reason (client disconnect, timeout, server-side error).
        // Without this cleanup we'd leak listeners over the JVM's
        // lifetime and eventually fan out every event to zombie
        // subscriptions.
        Runnable cleanup = () -> {
            redisListeners.removeMessageListener(listener, pattern);
            live.remove(subscriptionId);
            log.info("SSE subscription {} closed", subscriptionId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(err -> {
            log.debug("SSE subscription {} errored: {}", subscriptionId, err.getMessage());
            cleanup.run();
        });

        // Ping the client immediately so it knows the subscription is
        // live (a browser will otherwise wait for the first event to
        // fire its onopen; some proxies buffer the initial bytes).
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(subscriptionId))
                    .name("subscribed")
                    .data("{}"));
        } catch (IOException ignored) {
            // Client gone before we could even say hello — normal.
        }
        return emitter;
    }

    /**
     * Parse the topics query param. Blank / null / unknown-topic-only
     * → all known topics. Filters out unknown values silently.
     */
    private static Set<String> parseTopics(String csv) {
        if (csv == null || csv.isBlank()) return KNOWN_TOPICS;
        Set<String> requested = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(KNOWN_TOPICS::contains)
                .collect(Collectors.toSet());
        return requested.isEmpty() ? KNOWN_TOPICS : requested;
    }

    /**
     * Redis pub/sub {@link MessageListener} that relays matching
     * events to its bound {@link SseEmitter}. One instance per SSE
     * subscription — filtering + tenant scoping live here so the
     * SseEmitter's send path stays trivial.
     */
    private class SseRelayListener implements MessageListener {
        private final SseEmitter emitter;
        private final Set<String> topics;
        private final String callerTenant;
        private final boolean platform;
        private final long subscriptionId;

        SseRelayListener(SseEmitter emitter, Set<String> topics,
                          String callerTenant, boolean platform, long subscriptionId) {
            this.emitter = emitter;
            this.topics = topics;
            this.callerTenant = callerTenant;
            this.platform = platform;
            this.subscriptionId = subscriptionId;
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            // Channel format is "events.<topic>" — strip prefix,
            // check against the client's topic subscription.
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String topic = channel.startsWith(AppEventBus.CHANNEL_PREFIX)
                    ? channel.substring(AppEventBus.CHANNEL_PREFIX.length())
                    : channel;
            if (!topics.contains(topic)) return;

            String body = new String(message.getBody(), StandardCharsets.UTF_8);

            // Tenant filter — parse the payload just enough to find
            // "tenant":"<value>". A platform operator sees everything.
            // A tenant-scoped user sees only events with a matching
            // tenant OR events with no tenant (platform events).
            if (!platform && callerTenant != null) {
                Optional<String> eventTenant = extractTenant(body);
                if (eventTenant.isPresent()
                        && !callerTenant.equalsIgnoreCase(eventTenant.get())) {
                    return;   // drop — foreign tenant
                }
            }

            // Best-effort extract of eventType for the SSE event: line.
            String eventType = extractEventType(body).orElse("message");

            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(nextId.getAndIncrement()))
                        .name(eventType)
                        .data(body));
            } catch (IOException | IllegalStateException ex) {
                // Emitter closed (client gone). Cleanup runs via
                // onCompletion/onError; nothing more to do here.
                log.debug("SSE subscription {} send failed: {}", subscriptionId, ex.getMessage());
            }
        }
    }

    /** Extract {@code "tenant":"..."} from a JSON body without a full
     *  parse. Returns empty for null tenants or malformed input. */
    static Optional<String> extractTenant(String jsonBody) {
        return extractStringField(jsonBody, "tenant");
    }

    /** Extract {@code "eventType":"..."} from a JSON body. */
    static Optional<String> extractEventType(String jsonBody) {
        return extractStringField(jsonBody, "eventType");
    }

    /** Cheap best-effort {@code "field":"value"} extractor — avoids
     *  spinning up Jackson for every relayed message. Matches the
     *  simple shape our events emit (no escapes needed on the value). */
    private static Optional<String> extractStringField(String json, String field) {
        if (json == null) return Optional.empty();
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return Optional.empty();
        start += needle.length();
        int end = json.indexOf('"', start);
        if (end < 0) return Optional.empty();
        String value = json.substring(start, end);
        // "tenant":null shows up as "tenant":null (no quotes) — the
        // check above already excludes that shape.
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
