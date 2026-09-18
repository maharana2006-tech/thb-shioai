package com.multiship.backend.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * Sprint 51 FE-M3 — receiver for the SPA's client-side render errors.
 *
 * <p>The React {@code RouteErrorBoundary} and {@code AppErrorBoundary}
 * POST here on any thrown render error so ops sees the crash without
 * relying on operators to screenshot the browser console. Logs at WARN
 * only — no DB persistence in this pass; log aggregators handle it.
 *
 * <p>The endpoint is unauthenticated on purpose: crashes can happen on
 * the login page, on token expiry, or on the offline splash — the sink
 * has to accept reports before the operator is signed in. To keep it
 * from turning into an abuse vector, an in-memory per-IP token bucket
 * caps traffic at ~30/min/IP. Payload fields are truncated on the
 * client side (see {@code src/utils/errorReport.ts}) and again here
 * as a defence in depth.
 */
@Slf4j
@Tag(name = "Client errors", description = "Frontend render-error telemetry sink (unauthenticated, IP rate-limited)")
@RestController
@RequestMapping("/api/v1/client-errors")
public class ClientErrorReportController {

    /** Max chars of stack/componentStack to log per report. */
    private static final int MAX_STACK_CHARS = 2000;
    /** Max chars of message we accept. */
    private static final int MAX_MESSAGE_CHARS = 500;
    /** Reports per IP per {@link #WINDOW_MS} milliseconds. */
    private static final int MAX_PER_WINDOW = 30;
    private static final long WINDOW_MS = 60_000L;
    /** Cap the map so a scripted IP-spoofing attacker can't OOM the JVM.
     *  PR-P4 (PERF-M5): the pre-P4 raw {@code ConcurrentHashMap} used to
     *  {@code clear()} the ENTIRE map on overflow, giving an attacker
     *  with rotating X-Forwarded-For values a way to flush every legit
     *  IP's rate-limit window on every 10 001th request. The Caffeine
     *  wrap below evicts individual idle entries (10-min inactivity)
     *  instead + hard-caps at MAX_TRACKED_IPS via {@code maximumSize},
     *  so an attacker's spammed keys age out on their own and can't
     *  reset a legit user's counter. */
    private static final int MAX_TRACKED_IPS = 10_000;

    /**
     * PR-P4 (PERF-M5) — Caffeine cache with per-entry TTL + hard maximum
     * size. Entries auto-evict after 10 minutes of inactivity (much
     * larger than the 60-second rate-limit window, so a legit IP's
     * hits never expire under it), and the maximumSize cap enforces the
     * {@link #MAX_TRACKED_IPS} bound without the "clear-the-whole-map"
     * anti-pattern of the raw ConcurrentHashMap.
     */
    private final Cache<String, Queue<Long>> reportsByIp = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(MAX_TRACKED_IPS)
            .build();

    @Operation(summary = "Report a frontend render error",
            description = "Accepts a small JSON payload describing a React error boundary catch. "
                    + "Logs at WARN; returns 202. Rate-limited to 30 reports/minute/IP.")
    @SecurityRequirements
    @PostMapping
    public ResponseEntity<Void> report(@RequestBody ClientErrorDTO body,
                                       HttpServletRequest request) {
        String ip = resolveClientIp(request);
        if (!allow(ip)) {
            // Silently drop — do NOT surface a 429 that would trigger the
            // SPA's own error handler and loop back into telemetry.
            return ResponseEntity.accepted().build();
        }

        String path = truncate(body == null ? null : body.getPath(), MAX_MESSAGE_CHARS);
        String message = truncate(body == null ? null : body.getMessage(), MAX_MESSAGE_CHARS);
        String stack = truncate(body == null ? null : body.getStack(), MAX_STACK_CHARS);
        String componentStack = truncate(body == null ? null : body.getComponentStack(), MAX_STACK_CHARS);
        String userAgent = truncate(body == null ? null : body.getUserAgent(), MAX_MESSAGE_CHARS);
        String ts = body == null || body.getTs() == null ? Instant.now().toString() : body.getTs();

        log.warn("[client-error] ip={} ts={} path={} ua={} message={} stack={} componentStack={}",
                ip, ts, path, userAgent, message, stack, componentStack);

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** Sliding-window token check per source IP.
     *  PR-P4: Caffeine's {@code get(key, loader)} atomically fetches-or-creates
     *  the per-IP queue (equivalent to the pre-P4 {@code computeIfAbsent}).
     *  No manual overflow clear — {@code maximumSize} on the cache handles
     *  eviction one entry at a time via Window-TinyLFU. */
    private boolean allow(String ip) {
        long now = System.currentTimeMillis();
        Queue<Long> hits = reportsByIp.get(ip, k -> new LinkedList<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peek() > WINDOW_MS) {
                hits.poll();
            }
            if (hits.size() >= MAX_PER_WINDOW) {
                return false;
            }
            hits.offer(now);
            return true;
        }
    }

    /** X-Forwarded-For aware; falls back to the socket peer. */
    static String resolveClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        // Strip line breaks so a multi-line stack collapses into a single
        // log record — grep-friendly + prevents log-forging via CRLF.
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * Wire shape sent by {@code multiship-react/src/utils/errorReport.ts}.
     * Kept as a POJO (no validation annotations) — the endpoint is
     * best-effort and we prefer accepting a slightly-malformed report to
     * dropping useful crash info on the floor.
     */
    @Data
    public static class ClientErrorDTO {
        private String path;
        private String message;
        private String stack;
        private String componentStack;
        private String userAgent;
        private String ts;

        /** Debug hook for tests — mirrors default HashMap toString output. */
        public Map<String, String> asMap() {
            Map<String, String> m = new HashMap<>();
            m.put("path", path);
            m.put("message", message);
            m.put("stack", stack);
            m.put("componentStack", componentStack);
            m.put("userAgent", userAgent);
            m.put("ts", ts);
            return m;
        }
    }
}
