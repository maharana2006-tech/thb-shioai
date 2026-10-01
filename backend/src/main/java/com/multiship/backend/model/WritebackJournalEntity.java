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
 * D1 — one row per {@code ExternalSystemWritebackDispatcher} attempt.
 * See {@code V109} for column semantics.
 */
@Entity
@Table(name = "external_system_writeback_journal")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritebackJournalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_name", nullable = false, length = 120)
    private String connectionName;

    @Column(name = "system_type", length = 40)
    private String systemType;

    /** GENERATE | CLEAR. */
    @Column(nullable = false, length = 16)
    private String mode;

    /** PENDING | OK | SKIPPED | FAILED. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "client_code", length = 64)
    private String clientCode;

    @Column(name = "order_no")
    private Integer orderNo;

    @Column(name = "tracking_number", length = 120)
    private String trackingNumber;

    @Column(length = 40)
    private String source;

    @Column(length = 20)
    private String channel;

    /** Redacted payload snapshot — never contains secrets. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "ack_status", length = 16)
    private String ackStatus;

    @Column(name = "ack_detail", columnDefinition = "TEXT")
    private String ackDetail;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "retry_of_id")
    private Long retryOfId;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
