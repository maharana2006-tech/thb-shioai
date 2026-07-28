package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 45 — a single execution of a {@link ScheduledReport}. Rows
 * are kept for both DASHBOARD delivery (download link) and audit for
 * EMAIL / WEBHOOK deliveries.
 */
@Entity
@Table(name = "generated_report",
        indexes = @Index(name = "idx_generated_report_schedule", columnList = "schedule_id"))
@Data
public class GeneratedReport {

    public enum DeliveryStatus { PENDING, DELIVERED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to the schedule that produced this row. */
    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    /** Denormalised for cheap listing without the schedule join. */
    @Column(name = "tenant_id", length = 50)
    private String tenantId;

    @Column(nullable = false, length = 20)
    private String dataset;

    @Column(name = "filename", nullable = false, length = 200)
    private String filename;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** CSV payload (kept inline for simplicity — reports are typically small). */
    @Lob
    @Column(name = "csv_bytes")
    private byte[] csvBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    @Column(name = "delivery_message", length = 500)
    private String deliveryMessage;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
}
