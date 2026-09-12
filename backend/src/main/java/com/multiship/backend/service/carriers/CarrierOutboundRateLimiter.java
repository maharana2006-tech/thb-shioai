package com.multiship.backend.service.carriers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side outbound rate limiter for carrier API calls.
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
 * <p>Semantics — {@link #acquire(String)} BLOCKS the calling thread
 * until it's safe to make the next call for that carrier. Single-caller
 * cost is zero (no wait); N concurrent callers on the same carrier see
 * their calls serialized to 1/R apart. Setting {@code requests-per-second}
 * to {@code 0} disables the limiter for that carrier (behaves like the
 * pre-limiter code — every call fires immediately).
 */
@Slf4j
@Component
public class CarrierOutboundRateLimiter {

    /** Configured min gap in milliseconds per carrier (upper-cased key). */
    private final Map<String, Long> minGapMs = new ConcurrentHashMap<>();

    /** Next-eligible epoch-ms per carrier. Mutated under {@link #lock}. */
    private final Map<String, Long> nextEligibleMs = new ConcurrentHashMap<>();

    /** Serialises {@link #acquire(String)}'s read-modify-write of
     *  {@link #nextEligibleMs}. Cheap because acquire only holds it long
     *  enough to compute the new eligibility timestamp — the actual
     *  sleep happens outside the lock. */
    private final Object lock = new Object();

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
        configure("UPS", upsRps);
        configure("FEDEX", fedexRps);
        configure("DHL", dhlRps);
        configure("USPS", uspsRps);
        log.info("CarrierOutboundRateLimiter ready — UPS={}rps FEDEX={}rps DHL={}rps USPS={}rps "
                        + "(0 = disabled).",
                upsRps, fedexRps, dhlRps, uspsRps);
    }

    private void configure(String carrier, int requestsPerSecond) {
        if (requestsPerSecond <= 0) {
            minGapMs.remove(carrier);
        } else {
            minGapMs.put(carrier, 1000L / requestsPerSecond);
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
     * Test hook — override the min-gap for a carrier at runtime. Package-
     * private so unit tests can shrink the interval to microseconds.
     */
    void setRequestsPerSecondForTest(String carrier, int rps) {
        configure(carrier.toUpperCase(Locale.ROOT), rps);
        nextEligibleMs.remove(carrier.toUpperCase(Locale.ROOT));
    }
}
