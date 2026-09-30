package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A4.4 — one row per {@link com.multiship.backend.service.mail.NotificationService}
 * send attempt (SENT or FAILED). Retries create fresh rows and point at
 * the original via {@code retryOfId}.
 */
@Entity
@Table(name = "notification_delivery_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDeliveryLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_key", length = 60)
    private String templateKey;

    @Column(nullable = false, length = 255)
    private String recipient;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** SENT or FAILED. Enforced by CHECK constraint. */
    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "provider_kind", length = 20)
    private String providerKind;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** Self-FK — nullable on originals, set on retries. */
    @Column(name = "retry_of_id")
    private Long retryOfId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
