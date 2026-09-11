package com.multiship.backend.controller;

import com.multiship.backend.events.AppEventBus;
import com.multiship.backend.service.TenantScopeEnforcer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Server-Sent Events endpoint for real-time FE updates. Replaces the
 * 2-4s polling loops on BulkLabelModal and DataHistoryPage with a
 * single long-lived HTTP connection per operator.
 *
 * <p>Phase 4 — backed by Redis Streams (not pub/sub) so late-joining
 * subscribers can resume from {@code Last-Event-Id} without losing
 * events dropped during reconnect. The SSE {@code id:} line on every
 * event is the raw Redis Stream record id (e.g.
 * {@code 1699999999999-0}); the browser then sends
 * {@code Last-Event-Id: 1699999999999-0} on reconnect and we resume
 * XREAD from that offset.
 *
 * <p>Endpoint: {@code GET /api/v1/events/stream?topics=bulk-labels,import-batches}.
 * The stream is JWT-authenticated via the existing cookie flow.
 * Tenant filter drops events for other tenants at the wire so a
 * scoped USER never sees another tenant's activity.
 *
 * <p>Backpressure: {@link SseEmitter} has an internal queue; if the
 * client is slow the emitter times out and the subscription is torn
 * down. Redis Streams retain events for the {@link AppEventBus#STREAM_MAXLEN}
 * cap so a late reconnect can still resume the last N events.
 *
 * <p>Redis-absent behaviour: when the
 * {@link StreamMessageListenerContainer} bean isn't wired
 * ({@code REDIS_HOST} unset), this controller returns a 503-shaped
 * emitter so the FE {@code EventSource.onerror} fires and the FE
 * falls back to its polling path.
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
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamListeners;

    /** Per-connection SSE event-id counter — used for the initial
     *  "subscribed" ping (which doesn't come from Redis and so has
     *  no Stream RecordId of its own). Redis Stream IDs are used
     *  from event #2 onwards so Last-Event-Id resume works. */
    private final AtomicLong subscriptionCounter = new AtomicLong(1);

    /** Live subscriptions — kept so a shutdown hook could iterate. */
    private final ConcurrentHashMap<Long, SseEmitter> live = new ConcurrentHashMap<>();

    @Autowired
    public SseController(TenantScopeEnforcer tenantScope,
                        @Autowired(required = false)
                        StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamListeners) {
        this.tenantScope = tenantScope;
        this.streamListeners = streamListeners;
        if (streamListeners == null) {
            log.info("SseController: Redis not configured — /events/stream will return 503; "
                    + "FE falls back to polling.");
        }
    }

    /**
     * Open an SSE subscription. Long-lived HTTP response — the
     * connection stays open until the client disconnects or the
     * SSE_TIMEOUT_MS timer fires (browser then reconnects, replaying
     * from {@code Last-Event-Id}).
     */
    @Operation(summary = "Real-time backend event stream (SSE)",
            description = "Opens a long-lived text/event-stream connection. Server pushes typed events "
                    + "as backend state changes (bulk-label job status, import batch status). Filter by "
                    + "comma-separated topics query parameter; omit to receive all. Tenant-scoped: only "
                    + "events for the caller's tenant are delivered. On reconnect, the browser sends "
                    + "Last-Event-Id and we resume from that Redis Stream offset (Phase 4 durability).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "topics", required = false) String topicsCsv,
                              @RequestHeader(value = "Last-Event-Id", required = false) String lastEventId) {
        // Redis-absent: fail early with a well-known error so the FE
        // EventSource's onerror falls back to polling immediately.
        if (streamListeners == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.completeWithError(new IllegalStateException(
                    "SSE unavailable — Redis is not configured. Set REDIS_HOST to enable."));
            return emitter;
        }

        Set<String> topics = parseTopics(topicsCsv);
        String tenant = tenantScope.resolveScope().orElse(null);
        boolean platform = tenantScope.isPlatformOperator();
        long subscriptionId = subscriptionCounter.getAndIncrement();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Phase 4 — resume from Last-Event-Id when present. If the
        // browser reconnects after a network blip, it sends the id
        // of the last event it processed; we XREAD from immediately
        // after that offset so nothing is lost.
        //
        // Missing / malformed Last-Event-Id → ReadOffset.latest()
        // means "only new events from this moment on", the correct
        // behaviour for a fresh subscription.
        ReadOffset readOffset = parseReadOffset(lastEventId);
        StreamOffset<String> streamOffset = StreamOffset.create(AppEventBus.STREAM_KEY, readOffset);

        SseRelayListener listener = new SseRelayListener(
                emitter, topics, tenant, platform, subscriptionId);
        Subscription subscription = streamListeners.receive(streamOffset, listener);
        live.put(subscriptionId, emitter);
        log.info("SSE subscription {} opened (topics={}, tenant={}, platform={}, resume-from={})",
                subscriptionId, topics, tenant, platform, readOffset);

        // Detach the stream subscription when the emitter finishes
        // for ANY reason (client disconnect, timeout, server-side
        // error). Without this cleanup we'd leak Redis subscribers
        // over the JVM's lifetime.
        Runnable cleanup = () -> {
            try {
                subscription.cancel();
            } catch (Exception ex) {
                log.debug("Subscription cancel for {} threw: {}", subscriptionId, ex.getMessage());
            }
            live.remove(subscriptionId);
            log.info("SSE subscription {} closed", subscriptionId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(err -> {
            log.debug("SSE subscription {} errored: {}", subscriptionId, err.getMessage());
            cleanup.run();
        });

        // Send a subscribed ping immediately so the browser onopen
        // fires — some proxies otherwise buffer initial bytes and
        // the FE stays in 'connecting' state until the first real
        // event lands. Uses the per-connection counter for its id
        // (not a Redis Stream ID) — the browser sees it, updates
        // Last-Event-Id, and if the connection drops before the
        // first real event our Redis subscription resumes from
        // latest() (the parseReadOffset fallback handles the
        // non-Stream ID).
        try {
            emitter.send(SseEmitter.event()
                    .id("sub-" + subscriptionId)
                    .name("subscribed")
                    .data("{}"));
        } catch (IOException ignored) {
            // Client gone before we could even say hello — normal.
        }
        return emitter;
    }

    /**
     * Parse a Redis Stream ID from the Last-Event-Id header. Missing
     * or malformed → {@link ReadOffset#latest()} which means "only
     * new events from this moment". Redis Stream IDs are
     * {@code millis-seq} format (e.g. {@code 1699999999999-0}); we
     * do a light shape-check so a garbage header doesn't confuse
     * Spring Data Redis.
     */
    static ReadOffset parseReadOffset(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return ReadOffset.latest();
        if (lastEventId.startsWith("sub-")) {
            // Our "subscribed" ping id — has no meaning as a stream
            // offset. Fresh subscription semantics apply.
            return ReadOffset.latest();
        }
        // Redis Stream ID shape: N-N where both parts are non-negative
        // integers. Anything else, fall back to latest() rather than
        // let a bad header throw at XREAD time.
        int dash = lastEventId.indexOf('-');
        if (dash <= 0 || dash == lastEventId.length() - 1) return ReadOffset.latest();
        try {
            Long.parseLong(lastEventId.substring(0, dash));
            Long.parseLong(lastEventId.substring(dash + 1));
        } catch (NumberFormatException notAnId) {
            return ReadOffset.latest();
        }
        return ReadOffset.from(lastEventId);
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
     * Redis Stream listener that relays matching entries to its
     * bound {@link SseEmitter}. One instance per SSE subscription —
     * filtering + tenant scoping live here so the emitter's send
     * path stays trivial. Uses the raw Redis RecordId as the SSE
     * event id so the browser's Last-Event-Id-on-reconnect works.
     */
    private static class SseRelayListener
            implements org.springframework.data.redis.stream.StreamListener<String, MapRecord<String, String, String>> {

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
        public void onMessage(MapRecord<String, String, String> record) {
            String topic = record.getValue().get(AppEventBus.TOPIC_FIELD);
            if (topic == null || !topics.contains(topic)) return;

            String body = record.getValue().get(AppEventBus.PAYLOAD_FIELD);
            if (body == null) return;

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

            String eventType = extractEventType(body).orElse("message");
            String id = record.getId() == null ? "0-0" : record.getId().getValue();

            try {
                emitter.send(SseEmitter.event()
                        .id(id)
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
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
