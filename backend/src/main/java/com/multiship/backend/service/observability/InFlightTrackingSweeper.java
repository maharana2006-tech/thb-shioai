package com.multiship.backend.service.observability;

import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Perf P3 phase 0 scaffold — surfaces order_label_tracking rows left
 * in the IN_FLIGHT state past {@code carrier.tx-split.in-flight-timeout}.
 * P0 logs only; P5 flips the sweeper to resolve them (mark FAILED or
 * reconcile with the carrier via tracking lookup).
 *
 * <p>Mirrors {@code WritebackJournalSweeper}: @Scheduled tick, cluster-
 * wide advisory lock so only one node sweeps per tick, Pageable scan.
 *
 * <p>Logging is unconditional in P0 (the flag {@code carrier.tx-split-
 * phase-c} controls whether callers CREATE in-flight rows; this sweeper
 * only READS them, so there's nothing to gate yet). Pre-P1 the query
 * will always return empty — the WARN log self-documents when phase A
 * callers start landing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InFlightTrackingSweeper {

    static final int BATCH_LIMIT = 50;

    private final OrderTrackingRepository repo;

    @Value("${carrier.tx-split.in-flight-timeout:PT2M}")
    private Duration inFlightTimeout;

    @Scheduled(fixedDelayString = "${carrier.tx-split.sweeper-interval-ms:60000}",
               initialDelayString = "${carrier.tx-split.sweeper-initial-delay-ms:45000}")
    @Transactional
    public void sweep() {
        Boolean acquired = repo.tryAcquireInFlightSweeperLock();
        if (Boolean.FALSE.equals(acquired)) {
            return;
        }
        Instant cutoff = Instant.now().minus(inFlightTimeout);
        List<OrderTracking> stuck = repo.findStuckInFlight(cutoff, PageRequest.of(0, BATCH_LIMIT));
        if (stuck.isEmpty()) return;
        for (OrderTracking row : stuck) {
            log.warn("in-flight-sweeper: order={} stuck since {} (timeout={}); P5 will resolve, "
                    + "P0 only surfaces", row.getOrderNo(), row.getInFlightSince(), inFlightTimeout);
        }
        log.warn("in-flight-sweeper: {} stuck row(s) detected this tick", stuck.size());
    }
}
