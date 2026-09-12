package com.multiship.backend.service.carriers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side outbound rate limiter for carrier API calls, with
 * adaptive self-throttling on observed 429 density.
 *
 * <p>Motivation — real failure at 2026-09-11 20:06:20: the bulk-import
 * fan-out sent ~8 concurrent UPS {@code createShipment} calls in the same
 * second and UPS returned HTTP 429 {@code 10429 "Too Many Requests"} on
 * every one. The reactive cool-down in
 * {@link com.multiship.backend.service.OrderImportServiceImpl} kicks in
 * *after* a 429, so the very first burst always lands. Then the retry
 * pass fires the deferred groups all at once and trips UPS again.
 *
 * <p>This limiter fixes it proactively: each carrier gets a minimum
 * inter-request gap, so a burst of N concurrent workers is serialized
 * into a stream at rate R. UPS defaults to 3 req/sec (matches UPS
 * sandbox burst tolerance); tune per carrier via
 * {@code carrier.rate-limit.<carrier>.requests-per-second} in
 * application.properties.
 *
 * <p><b>Adaptive throttling (Batch #11 post-mortem, 2026-09-12):</b>
 * Even with the base rate configured, UPS sandbox's per-hour quota can
 * be exhausted mid-batch — a 10k-row bulk import at 3 rps hits ~4x the
 * sandbox quota window. When {@link #notifyRateLimited(String)} is
 * called from a connector's 429 handler, the limiter records the
 * timestamp; if {@link #RATE_LIMIT_TRIGGER_COUNT} events land inside
 * a {@link #RATE_LIMIT_WINDOW_MS} window, the outbound rate for that
 * carrier is HALVED (floor {@link #ADAPTIVE_FLOOR_RPS} rps). When the
 * window has been quiet for {@link #RATE_LIMIT_RESTORE_MS}, the rate
 * restores to the configured baseline. Prevents a persistent 429 storm
 * from becoming an uncoordinated stampede that keeps tripping the
 * carrier's quota over and over.
 *
 * <p>Semantics — {@link #acquire(String)} BLOCKS the calling thread
 * until it's safe to make the next call for that carrier. Single-caller
 * cost is zero (no wait); N concurrent callers on the same carrier see
 * their calls serialized to 1/R apart. Setting {@code requests-per-second}
 * to {@code 0} disables the limiter (and adaptive throttling) for that
 * carrier — behaves like the pre-limiter code.
 */
@Slf4j
@Component
public class CarrierOutboundRateLimiter {

    /** N 429 events in the window triggers the adaptive halve. */
    static final int RATE_LIMIT_TRIGGER_COUNT = 3;
    /** Sliding window duration for 429 detection (ms). */
    static final long RATE_LIMIT_WINDOW_MS = 60_000L;
    /** Restore the baseline rate after this many ms of "no 429" quiet. */
    static final long RATE_LIMIT_RESTORE_MS = 90_000L;
    /** Minimum adaptive rate floor — never throttle below 1 rps or the
     *  batch would never make forward progress. */
    static final int ADAPTIVE_FLOOR_RPS = 1;

    /** Baseline (configured) rps per carrier — used as the target when
     *  restoring after an adaptive throttle. */
    private final Map<String, Integer> baselineRps = new ConcurrentHashMap<>();

    /** Effective (possibly adapted-down) rps per carrier. */
    private final Map<String, Integer> effectiveRps = new ConcurrentHashMap<>();

    /** Configured min gap in milliseconds per carrier (upper-cased key).
     *  Derived from {@link #effectiveRps}; updated whenever effectiveRps
     *  changes so {@link #acquire(String)} can read a single value. */
    private final Map<String, Long> minGapMs = new ConcurrentHashMap<>();

    /** Next-eligible epoch-ms per carrier. Mutated under {@link #lock}. */
    private final Map<String, Long> nextEligibleMs = new ConcurrentHashMap<>();

    /** Recent 429-observation timestamps per carrier, keyed by
     *  upper-case carrier code. Values are epoch-ms; entries older than
     *  the sliding window get pruned on each recompute. Guarded by
     *  {@link #adaptiveLock}. */
    private final Map<String, Deque<Long>> recent429Ms = new ConcurrentHashMap<>();

    /** Serialises {@link #acquire(String)}'s read-modify-write of
     *  {@link #nextEligibleMs}. Cheap because acquire only holds it long
     *  enough to compute the new eligibility timestamp — the actual
     *  sleep happens outside the lock. */
    private final Object lock = new Object();

    /** Serialises the adaptive-throttle state changes so a burst of
     *  concurrent 429 notifications don't race on the effectiveRps
     *  recompute. Separate from {@link #lock} so acquire() calls don't
     *  block on the adaptive-recompute path. */
    private final Object adaptiveLock = new Object();

    /** UPS default — 3 req/sec matches UPS sandbox's burst tolerance
     *  observed on the 2026-09-11 failure. Production accounts can go
     *  higher; tune via {@code carrier.rate-limit.ups.requests-per-second}. */
    @Value("${carrier.rate-limit.ups.requests-per-second:3}")
    private int upsRps;

    /** FedEx default — FedEx has generally been more permissive than UPS
     *  in our load tests but we throttle to a moderate 5 req/sec to keep
     *  the same pattern in place should we ever hit their limit. */
    @Value("${carrier.rate-limit.fedex.requests-per-second:5}")
    private int fedexRps;

    /** DHL default — matches UPS conservatism. */
    @Value("${carrier.rate-limit.dhl.requests-per-second:3}")
    private int dhlRps;

    /** USPS default — Stamps.com SWSIM has been observed accommodating
     *  higher rates but we mirror UPS as a safe baseline. */
    @Value("${carrier.rate-limit.usps.requests-per-second:3}")
    private int uspsRps;

    /** Bind the configured rates into {@link #minGapMs} after DI. */
    @jakarta.annotation.PostConstruct
    void init() {
        configureBaseline("UPS", upsRps);
        configureBaseline("FEDEX", fedexRps);
        configureBaseline("DHL", dhlRps);
        configureBaseline("USPS", uspsRps);
        log.info("CarrierOutboundRateLimiter ready — UPS={}rps FEDEX={}rps DHL={}rps USPS={}rps "
                        + "(0 = disabled). Adaptive: halve on {}× 429 in {}s window, restore after {}s quiet.",
                upsRps, fedexRps, dhlRps, uspsRps,
                RATE_LIMIT_TRIGGER_COUNT, RATE_LIMIT_WINDOW_MS / 1000, RATE_LIMIT_RESTORE_MS / 1000);
    }

    /** Set both baseline and effective rps for a carrier. Called at
     *  startup + tests. */
    private void configureBaseline(String carrier, int requestsPerSecond) {
        baselineRps.put(carrier, requestsPerSecond);
        applyRps(carrier, requestsPerSecond);
    }

    /** Update effectiveRps + minGapMs for a carrier. */
    private void applyRps(String carrier, int rps) {
        if (rps <= 0) {
            effectiveRps.remove(carrier);
            minGapMs.remove(carrier);
        } else {
            effectiveRps.put(carrier, rps);
            minGapMs.put(carrier, 1000L / rps);
        }
    }

    /**
     * Wait until it's safe to make the next call for {@code carrier}.
     * Returns immediately if the carrier isn't throttled or the previous
     * call was long enough ago. Otherwise sleeps until the next slot.
     *
     * <p>If the current thread is interrupted while waiting, the interrupt
     * flag is restored and the method returns without further sleep — the
     * caller is expected to check its own cancellation state before making
     * the actual carrier call.
     */
    public void acquire(String carrier) {
        if (carrier == null) return;
        String key = carrier.trim().toUpperCase(Locale.ROOT);
        // Opportunistic restore check — a caller entering acquire is
        // proof the batch is still running, so a periodic no-op check
        // here is cheaper than a background scheduler.
        maybeRestoreBaseline(key);
        Long gap = minGapMs.get(key);
        if (gap == null || gap <= 0) return;

        long now = System.currentTimeMillis();
        long waitUntil;
        synchronized (lock) {
            long next = nextEligibleMs.getOrDefault(key, now);
            waitUntil = Math.max(next, now);
            nextEligibleMs.put(key, waitUntil + gap);
        }
        long sleepMs = waitUntil - now;
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                // Restore the interrupt flag so any downstream blocking
                // operation (or the caller's cancellation check) can
                // observe it. Don't rethrow — this method is called from
                // both interruptible and non-interruptible contexts, and
                // callers already have their own error paths.
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Adaptive-throttle hook. Called by a connector's 429 handler to
     * record that the carrier rejected a request with "Too Many
     * Requests." If {@link #RATE_LIMIT_TRIGGER_COUNT} events accumulate
     * inside {@link #RATE_LIMIT_WINDOW_MS}, the carrier's effective rps
     * is halved (floor {@link #ADAPTIVE_FLOOR_RPS}). No-op for a
     * carrier without a configured baseline.
     */
    public void notifyRateLimited(String carrier) {
        if (carrier == null) return;
        String key = carrier.trim().toUpperCase(Locale.ROOT);
        Integer baseline = baselineRps.get(key);
        if (baseline == null || baseline <= 0) return;
        long now = System.currentTimeMillis();
        synchronized (adaptiveLock) {
            Deque<Long> window = recent429Ms.computeIfAbsent(key, k -> new ArrayDeque<>());
            window.addLast(now);
            long cutoff = now - RATE_LIMIT_WINDOW_MS;
            while (!window.isEmpty() && window.peekFirst() < cutoff) window.pollFirst();
            if (window.size() >= RATE_LIMIT_TRIGGER_COUNT) {
                int current = effectiveRps.getOrDefault(key, baseline);
                int nextRps = Math.max(ADAPTIVE_FLOOR_RPS, current / 2);
                if (nextRps < current) {
                    log.warn("Adaptive throttle for {} — {}× 429 in the last {}s. Reducing outbound "
                                    + "rate from {} rps to {} rps.",
                            key, window.size(), RATE_LIMIT_WINDOW_MS / 1000, current, nextRps);
                    applyRps(key, nextRps);
                }
            }
        }
    }

    /** Restore the baseline rps if the window has been quiet long
     *  enough. Cheap — called opportunistically from {@link #acquire}. */
    private void maybeRestoreBaseline(String key) {
        Integer baseline = baselineRps.get(key);
        if (baseline == null || baseline <= 0) return;
        Integer current = effectiveRps.get(key);
        if (current == null || current.equals(baseline)) return;
        long now = System.currentTimeMillis();
        synchronized (adaptiveLock) {
            Deque<Long> window = recent429Ms.get(key);
            long newestEvent = (window == null || window.isEmpty()) ? 0L : window.peekLast();
            if (newestEvent > 0 && now - newestEvent < RATE_LIMIT_RESTORE_MS) return;
            // Quiet period elapsed — restore the baseline. Log so ops
            // can see the recovery signal on the same channel as the
            // throttle-down message.
            log.info("Adaptive throttle for {} — {}s quiet, restoring outbound rate from {} rps "
                            + "to baseline {} rps.",
                    key, RATE_LIMIT_RESTORE_MS / 1000, current, baseline);
            applyRps(key, baseline);
            if (window != null) window.clear();
        }
    }

    /**
     * Test hook — override the baseline rps for a carrier at runtime.
     * Package-private so unit tests can shrink the interval and drive
     * both the initial-configure and adaptive paths.
     */
    void setRequestsPerSecondForTest(String carrier, int rps) {
        String key = carrier.toUpperCase(Locale.ROOT);
        configureBaseline(key, rps);
        nextEligibleMs.remove(key);
        synchronized (adaptiveLock) {
            Deque<Long> w = recent429Ms.get(key);
            if (w != null) w.clear();
        }
    }

    /** Test hook — reveal current effective rps for assertions. */
    int currentRpsForTest(String carrier) {
        return effectiveRps.getOrDefault(carrier.toUpperCase(Locale.ROOT), 0);
    }
}
