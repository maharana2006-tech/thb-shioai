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

    private final WritebackJournalRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WritebackJournalEntity recordPending(String connectionName, String systemType,
                                                String mode, WritebackPayload payload) {
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
                .attemptNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WritebackJournalEntity recordPending(String connectionName, String systemType,
                                                WritebackClearRequest req) {
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
            row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            repo.save(row);
        });
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
