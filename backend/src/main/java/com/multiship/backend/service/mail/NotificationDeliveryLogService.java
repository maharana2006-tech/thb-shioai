package com.multiship.backend.service.mail;

import com.multiship.backend.model.NotificationDeliveryLogEntity;
import com.multiship.backend.repository.NotificationDeliveryLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * A4.4 — narrow writer + reader wrapper over
 * {@link NotificationDeliveryLogRepository}. The recording methods use
 * {@code REQUIRES_NEW} so a caller's failing transaction (which typically
 * causes the failure log entry in the first place) doesn't roll back the
 * log row too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryLogService {

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    private final NotificationDeliveryLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationDeliveryLogEntity recordSent(String templateKey, String to,
                                                    String subject, String body,
                                                    String providerKind, Long providerId,
                                                    Integer latencyMs, Long retryOfId) {
        return repo.save(NotificationDeliveryLogEntity.builder()
                .templateKey(templateKey)
                .recipient(to)
                .subject(subject)
                .body(body)
                .status(STATUS_SENT)
                .providerKind(providerKind)
                .providerId(providerId)
                .latencyMs(latencyMs)
                .retryOfId(retryOfId)
                .sentAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationDeliveryLogEntity recordFailed(String templateKey, String to,
                                                      String subject, String body,
                                                      String providerKind, Long providerId,
                                                      Integer latencyMs, String errorMessage,
                                                      Long retryOfId) {
        String msg = errorMessage == null ? "" : errorMessage;
        return repo.save(NotificationDeliveryLogEntity.builder()
                .templateKey(templateKey)
                .recipient(to)
                .subject(subject)
                .body(body)
                .status(STATUS_FAILED)
                .providerKind(providerKind)
                .providerId(providerId)
                .errorMessage(msg.length() > 4000 ? msg.substring(0, 4000) : msg)
                .latencyMs(latencyMs)
                .retryOfId(retryOfId)
                .sentAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
    }
}
