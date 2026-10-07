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
import java.time.LocalDateTime;
import java.util.List;

/**
 * Perf P3 phase 5 — resolves {@code order_label_tracking} rows left in
 * the IN_FLIGHT state past {@code carrier.tx-split.in-flight-timeout}.
 * These are the "process crashed between phase B (carrier HTTP) and
 * phase C (persist)" survivors: the carrier call may have succeeded,
 * failed, or never completed; the local DB has no way to know without
 * asking the carrier. Marking them ERROR with an actionable message is
 * the safe default — ops must query the carrier for the real state
 * before voiding/retrying, so we DON'T silently assume failure.
 *
 * <p>Mirrors {@code WritebackJournalSweeper}: @Scheduled tick, cluster-
 * wide advisory lock so only one node sweeps per tick, Pageable scan.
 *
 * <p>ponytail: naive "mark ERROR" resolution — a carrier tracking-
 * lookup round-trip would resolve many of these to GENERATED when the
 * HTTP response was lost but the label actually printed. Upgrade when
 * stuck-row counts become operationally painful; for now, the WARN log
 * tells ops which orders need manual reconciliation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InFlightTrackingSweeper {

    static final int BATCH_LIMIT = 50;
    private static final String STUCK_ERROR_MESSAGE =
            "Carrier dispatch did not complete within the in-flight timeout. "
            + "The carrier may or may not have accepted the shipment — query the "
            + "carrier for tracking state before voiding or retrying.";

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
        LocalDateTime now = LocalDateTime.now();
        for (OrderTracking row : stuck) {
            Instant startedAt = row.getInFlightSince();
            // Skip rows already settled by a parallel phase C commit between
            // the SELECT and our UPDATE — defensive, shouldn't happen under
            // the advisory lock + partial-index query but survives a race.
            if (startedAt == null) continue;
            row.setStatus("ERROR");
            row.setInFlightSince(null);
            row.setIsLabelGenerated(false);
            row.setErrorMessage(STUCK_ERROR_MESSAGE);
            row.setUpdatedAt(now);
            repo.save(row);
            log.warn("in-flight-sweeper: order={} resolved from stuck IN_FLIGHT (started {}, timeout={}) → ERROR. {}",
                    row.getOrderNo(), startedAt, inFlightTimeout, STUCK_ERROR_MESSAGE);
        }
        log.warn("in-flight-sweeper: {} stuck row(s) resolved this tick", stuck.size());
    }
}
