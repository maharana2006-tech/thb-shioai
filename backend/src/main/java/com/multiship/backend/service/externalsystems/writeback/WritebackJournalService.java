package com.multiship.backend.service.externalsystems.writeback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.WritebackJournalEntity;
import com.multiship.backend.repository.WritebackJournalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * D1 — narrow writer over {@link WritebackJournalRepository}.
 *
 * <p>Instrumented into {@link ExternalSystemWritebackDispatcher}:
 * <ol>
 *   <li>{@link #recordPending} inserts a PENDING row before the
 *       connector call.</li>
 *   <li>On completion {@link #recordAck} updates the same row with the
 *       {@link WritebackAck} + latency.</li>
 *   <li>On exception {@link #recordFailure} updates the row with the
 *       thrown message.</li>
 * </ol>
 *
 * <p>All three methods use {@code REQUIRES_NEW} so a caller's failing
 * transaction (typically the failure we're recording) doesn't roll
 * back the journal row itself — parity with
 * {@link com.multiship.backend.service.mail.NotificationDeliveryLogService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WritebackJournalService {

    public static final String MODE_GENERATE = "GENERATE";
    public static final String MODE_CLEAR = "CLEAR";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";
    /**
     * PR-I3 (audit {@code [[inactive-external-system-skip]]}) — terminal
     * status for writeback attempts the dispatcher chose not to make
     * because the backing {@code external_system_connection} row was
     * {@code active=false} at dispatch time. Distinct from
     * {@link #STATUS_SKIPPED} (connector-side "nothing to do") because
     * the SKIPPED_INACTIVE path represents an operator-chosen integration
     * off-state, not a connector decision — it needs its own row so
     * audit queries can distinguish "we had the data but the operator
     * had the integration off" from "the connector didn't apply."
     */
    public static final String STATUS_SKIPPED_INACTIVE = "SKIPPED_INACTIVE";

    // D1b — retry policy. ponytail: global cap + exponential backoff;
    // promote to a retry_policy table if per-connection tuning is ever
    // asked for. Values picked to match the ops-stated feel for the NDS
    // writer (first retry minutes, giving a transient Oracle hiccup
    // time to recover) and cap at an hour so no row sits idle for days.
    static final int MAX_ATTEMPTS = 5;
    static final int BACKOFF_CAP_MINUTES = 60;

    private final WritebackJournalRepository repo;
    private final ObjectMapper objectMapper;

    public WritebackJournalEntity recordPending(String connectionName, String systemType,
                                                String mode, WritebackPayload payload) {
        return recordPending(connectionName, systemType, mode, payload, null);
    }

    public WritebackJournalEntity recordPending(String connectionName, String systemType,
                                                WritebackClearRequest req) {
        return recordPending(connectionName, systemType, req, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WritebackJournalEntity recordPending(String connectionName, String systemType,
                                                String mode, WritebackPayload payload,
                                                Long retryOfId) {
        int attempt = nextAttempt(retryOfId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repo.save(WritebackJournalEntity.builder()
                .connectionName(connectionName)
                .systemType(systemType)
                .mode(mode)
                .status(STATUS_PENDING)
                .clientCode(payload == null ? null : payload.clientCode())
                .orderNo(payload == null ? null : payload.orderNo())
                .trackingNumber(payload == null ? null : payload.trackingNumber())
                .source(payload == null ? null : payload.source())
                .channel(payload == null ? null : payload.channel())
                .payloadJson(safeSerialize(payload))
                .attemptNumber(attempt)
                .retryOfId(retryOfId)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WritebackJournalEntity recordPending(String connectionName, String systemType,
                                                WritebackClearRequest req, Long retryOfId) {
        int attempt = nextAttempt(retryOfId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repo.save(WritebackJournalEntity.builder()
                .connectionName(connectionName)
                .systemType(systemType)
                .mode(MODE_CLEAR)
                .status(STATUS_PENDING)
                .clientCode(req == null ? null : req.clientCode())
                .orderNo(req == null ? null : req.orderNo())
                .trackingNumber(req == null ? null : req.trackingNumber())
                .source(req == null ? null : req.source())
                .channel(req == null ? null : req.channel())
                .payloadJson(safeSerialize(req))
                .attemptNumber(attempt)
                .retryOfId(retryOfId)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * PR-I3 — journal a GENERATE attempt that was skipped because the
     * backing connection is inactive. No connector call made; writes a
     * terminal {@link #STATUS_SKIPPED_INACTIVE} row so admin queries +
     * dashboards can show "integration off" without reading the log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WritebackJournalEntity recordSkippedInactive(String connectionName, String systemType,
                                                         String mode, WritebackPayload payload) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repo.save(WritebackJournalEntity.builder()
                .connectionName(connectionName)
                .systemType(systemType)
                .mode(mode)
                .status(STATUS_SKIPPED_INACTIVE)
                .ackDetail("connection '" + connectionName + "' inactive at dispatch time")
                .clientCode(payload == null ? null : payload.clientCode())
                .orderNo(payload == null ? null : payload.orderNo())
                .trackingNumber(payload == null ? null : payload.trackingNumber())
                .source(payload == null ? null : payload.source())
                .channel(payload == null ? null : payload.channel())
                .payloadJson(safeSerialize(payload))
                .attemptNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** PR-I3 — CLEAR-mode variant of {@link #recordSkippedInactive}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WritebackJournalEntity recordSkippedInactive(String connectionName, String systemType,
                                                         WritebackClearRequest req) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repo.save(WritebackJournalEntity.builder()
                .connectionName(connectionName)
                .systemType(systemType)
                .mode(MODE_CLEAR)
                .status(STATUS_SKIPPED_INACTIVE)
                .ackDetail("connection '" + connectionName + "' inactive at dispatch time")
                .clientCode(req == null ? null : req.clientCode())
                .orderNo(req == null ? null : req.orderNo())
                .trackingNumber(req == null ? null : req.trackingNumber())
                .source(req == null ? null : req.source())
                .channel(req == null ? null : req.channel())
                .payloadJson(safeSerialize(req))
                .attemptNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAck(Long journalId, WritebackAck ack, int latencyMs) {
        if (journalId == null) return;
        repo.findById(journalId).ifPresent(row -> {
            row.setAckStatus(ack == null ? null : ack.status().name());
            row.setAckDetail(clamp(ack == null ? null : ack.detail(), 4000));
            row.setLatencyMs(latencyMs);
            row.setStatus(mapAckStatus(ack));
            row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            repo.save(row);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long journalId, String errorMessage, int latencyMs) {
        if (journalId == null) return;
        repo.findById(journalId).ifPresent(row -> {
            row.setStatus(STATUS_FAILED);
            row.setErrorMessage(clamp(errorMessage, 4000));
            row.setLatencyMs(latencyMs);
            // D1b — schedule the sweeper to pick this row up again when
            // the backoff window closes, as long as we're under the
            // attempt ceiling. Terminal failure (attempt >= MAX) leaves
            // next_retry_at null so the sweeper ignores it; manual
            // admin retry still works via the controller.
            if (row.getAttemptNumber() != null && row.getAttemptNumber() < MAX_ATTEMPTS) {
                row.setNextRetryAt(nextRetryAt(row.getAttemptNumber()));
            }
            row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            repo.save(row);
        });
    }

    /**
     * D1b — null the row's {@code next_retry_at} atomically so a second
     * sweeper tick (or multi-node cluster) won't re-pick the same row
     * while a retry is in-flight. ponytail: single-UPDATE optimistic
     * reservation; add a {@code pg_advisory_xact_lock} if multi-node
     * contention ever shows up as duplicate retries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveForRetry(Long journalId) {
        if (journalId == null) return false;
        return repo.clearNextRetryAt(journalId) == 1;
    }

    private int nextAttempt(Long retryOfId) {
        if (retryOfId == null) return 1;
        return repo.findById(retryOfId)
                .map(r -> r.getAttemptNumber() == null ? 2 : r.getAttemptNumber() + 1)
                .orElse(1);
    }

    private static LocalDateTime nextRetryAt(int attemptNumber) {
        long minutes = Math.min(1L << Math.min(attemptNumber, 30), BACKOFF_CAP_MINUTES);
        return LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes);
    }

    private static String mapAckStatus(WritebackAck ack) {
        if (ack == null) return STATUS_FAILED;
        return switch (ack.status()) {
            case OK -> STATUS_OK;
            case SKIPPED -> STATUS_SKIPPED;
            case FAILED -> STATUS_FAILED;
        };
    }

    private String safeSerialize(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            log.warn("writeback-journal: payload serialization failed ({}): {}",
                    o.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    private static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
