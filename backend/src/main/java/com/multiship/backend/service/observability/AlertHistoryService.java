package com.multiship.backend.service.observability;

import com.multiship.backend.model.AlertHistoryEntity;
import com.multiship.backend.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * V113 — narrow writer for the alert_history table.
 *
 * <p>REQUIRES_NEW so a caller's failing transaction (typically the alert
 * is itself a failure) doesn't roll back the audit record. Mirrors
 * WritebackJournalService + NotificationDeliveryLogService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertHistoryService {

    private final AlertHistoryRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String source, Long orderNo, String tenantCode,
                       Long importBatchId, String reason) {
        if (source == null || source.isBlank()) return;
        try {
            repo.save(AlertHistoryEntity.builder()
                    .firedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .source(source)
                    .targetOrderNo(orderNo)
                    .tenantCode(tenantCode)
                    .importBatchId(importBatchId)
                    .reason(clamp(reason, 4000))
                    .build());
        } catch (Exception ex) {
            // Never let an alert-persist failure propagate — ring buffer
            // + log line remain as fallback.
            log.warn("alert-history: persist failed for source={} reason={}: {}",
                    source, reason, ex.getMessage());
        }
    }

    private static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
