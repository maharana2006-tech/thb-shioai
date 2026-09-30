package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One "Automatic label" run over a DTC batch (V102). Claimed by
 * {@code DtcGenerationWorker} via SELECT FOR UPDATE SKIP LOCKED, executed by
 * {@code DtcLabelGenerationService} row-by-row. Mirrors import_generation_job.
 */
@Entity
@Table(name = "dtc_generation_job", indexes = {
    @Index(name = "idx_dtc_gen_job_claim", columnList = "status, queued_at"),
    @Index(name = "idx_dtc_gen_job_batch", columnList = "tenant_id, batch_id")
})
@Data
@NoArgsConstructor
public class DtcGenerationJob {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "batch_id", nullable = false)
    private BigDecimal batchId;

    @Column(name = "status", nullable = false)
    private String status = QUEUED;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows = 0;

    @Column(name = "processed_rows", nullable = false)
    private Integer processedRows = 0;

    @Column(name = "generated_count", nullable = false)
    private Integer generatedCount = 0;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    @Column(name = "skipped_count", nullable = false)
    private Integer skippedCount = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt = LocalDateTime.now();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
