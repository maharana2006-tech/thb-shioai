package com.multiship.backend.service;

import com.multiship.backend.model.PrinterDiscovered;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PR-Printer-R2 — in-memory pub-sub for the printer-discovery SSE
 * feed. Subscribers register per tenantCode; every call to
 * {@link PrinterScanService#upsertDiscovered} fans out to the tenant's
 * subscriber list after the DB save.
 *
 * <p>Design notes:
 * <ul>
 *   <li>{@link CopyOnWriteArrayList} per tenant — subscribe/unsubscribe
 *       is rare, publish is not; COW's read-side cost is zero.</li>
 *   <li>30-minute SseEmitter timeout — client is expected to auto-
 *       reconnect on close, so a bounded timeout stops dead-client
 *       emitters from leaking indefinitely.</li>
 *   <li>Keep-alive comment every 30s via a shared scheduler — most
 *       load balancers idle-timeout HTTP at 60s.</li>
 *   <li>Publish is fire-and-forget with per-emitter try/catch — one
 *       misbehaving client cannot block the ingest thread or crash
 *       the fan-out for the other subscribers.</li>
 * </ul>
 *
 * <p>Not backed by Redis / cross-node — this is single-node MVP.
 * When we scale to multiple app instances, either (a) sticky-session
 * the SSE endpoint via LB, or (b) swap in Redis pub-sub as the fan-out.
 */
@Slf4j
@Component
public class PrinterDiscoveryEventPublisher {

    static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L; // 30min
    static final long KEEPALIVE_INTERVAL_S = 30L;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> byTenant = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "printer-discovered-sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    public PrinterDiscoveryEventPublisher() {
        keepAlive.scheduleAtFixedRate(this::sendKeepAliveToAll,
                KEEPALIVE_INTERVAL_S, KEEPALIVE_INTERVAL_S, TimeUnit.SECONDS);
    }

    /**
     * Register a new subscriber. The returned emitter self-cleans on
     * completion / timeout / error — no manual unregister needed on the
     * caller side.
     */
    public SseEmitter subscribe(String tenantCode) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list = byTenant.computeIfAbsent(
                tenantCode.toUpperCase(), k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            log.debug("printer-discovered SSE subscriber removed: tenant={} remaining={}",
                    tenantCode, list.size());
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());

        // Send an initial `hello` event so the client knows the stream
        // is live (some proxies buffer until the first byte).
        try {
            emitter.send(SseEmitter.event()
                    .name("hello")
                    .data("{\"connected\":true}", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("printer-discovered SSE hello failed (client gone before first read): {}", e.toString());
            list.remove(emitter);
        }
        log.debug("printer-discovered SSE subscriber added: tenant={} total={}",
                tenantCode, list.size());
        return emitter;
    }

    /**
     * Fan out a discovered row to every current subscriber for the
     * tenant. Called from {@link PrinterScanService#upsertDiscovered}
     * AFTER the DB save so subscribers never see a row that then rolls
     * back.
     */
    public void publish(String tenantCode, PrinterDiscovered row) {
        CopyOnWriteArrayList<SseEmitter> list = byTenant.get(tenantCode.toUpperCase());
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(row.getId()))
                        .name("discovered")
                        .data(row, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // Any send failure = subscriber gone. Remove + swallow.
                log.debug("printer-discovered SSE send failed (subscriber pruned): {}", e.toString());
                emitter.completeWithError(e);
            }
        }
    }

    /** Test / metrics hook. */
    int subscriberCount(String tenantCode) {
        CopyOnWriteArrayList<SseEmitter> list = byTenant.get(tenantCode.toUpperCase());
        return list == null ? 0 : list.size();
    }

    private void sendKeepAliveToAll() {
        for (CopyOnWriteArrayList<SseEmitter> list : byTenant.values()) {
            for (SseEmitter emitter : list) {
                try {
                    // A comment-only event — SSE spec explicitly allows
                    // ": comment\n" heartbeats and clients ignore them.
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        keepAlive.shutdownNow();
        for (CopyOnWriteArrayList<SseEmitter> list : byTenant.values()) {
            list.forEach(SseEmitter::complete);
            list.clear();
        }
        byTenant.clear();
    }
}
