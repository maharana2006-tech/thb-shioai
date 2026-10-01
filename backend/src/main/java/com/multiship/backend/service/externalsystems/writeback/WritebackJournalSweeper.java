package com.multiship.backend.service.externalsystems.writeback;

import com.multiship.backend.model.WritebackJournalEntity;
import com.multiship.backend.repository.WritebackJournalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * D1b — scheduled sweeper for FAILED writeback-journal rows whose
 * backoff window has closed. Picks up to {@value #BATCH_LIMIT} rows per
 * tick, reserves each atomically (null the {@code next_retry_at} via a
 * single UPDATE), then hands to
 * {@link ExternalSystemWritebackDispatcher#redispatch} which creates a
 * fresh chained row and fires the async dispatch.
 *
 * <p>Attempts past {@link WritebackJournalService#MAX_ATTEMPTS} are left
 * with {@code next_retry_at = null} by {@code recordFailure} and are
 * never picked here — operator must {@code POST /{id}/retry} to force.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WritebackJournalSweeper {

    /** How many due rows to dispatch per tick. ponytail: per-connection
     *  tuning if one connection ever floods the queue. */
    static final int BATCH_LIMIT = 20;

    private final WritebackJournalRepository repo;
    private final WritebackJournalService journal;
    private final ExternalSystemWritebackDispatcher dispatcher;

    /** Interval between sweeps. Defaults to one minute. */
    @Value("${multiship.writeback.sweeper.interval-ms:60000}")
    private long intervalMs;

    @Scheduled(fixedDelayString = "${multiship.writeback.sweeper.interval-ms:60000}",
               initialDelayString = "${multiship.writeback.sweeper.initial-delay-ms:30000}")
    @Transactional
    public void sweep() {
        // D1b multi-node — cluster-wide advisory lock so only one node
        // sweeps per tick. Transaction-scoped: releases automatically on
        // method exit. Single-node + mocked-DB tests see null (repo mock
        // returns null for the SELECT) and treat it as "lock acquired".
        Boolean acquired = repo.tryAcquireSweeperLock();
        if (Boolean.FALSE.equals(acquired)) {
            return;
        }
        List<WritebackJournalEntity> due = repo.findDueForRetry(
                LocalDateTime.now(ZoneOffset.UTC), PageRequest.of(0, BATCH_LIMIT));
        if (due.isEmpty()) return;
        int dispatched = 0;
        for (WritebackJournalEntity row : due) {
            if (!journal.reserveForRetry(row.getId())) {
                // Another tick (or node) grabbed it between query + UPDATE.
                continue;
            }
            try {
                dispatcher.redispatch(row);
                dispatched++;
            } catch (Exception ex) {
                // Deserialization or dispatch-queue failure — leave the
                // row reserved (next_retry_at=null) so we don't hot-loop.
                // Operator can inspect + force via POST /{id}/retry.
                log.warn("writeback-sweeper: redispatch failed for row id={}: {}",
                        row.getId(), ex.getMessage());
            }
        }
        if (dispatched > 0) {
            log.info("writeback-sweeper: dispatched {} due row(s)", dispatched);
        }
    }
}
