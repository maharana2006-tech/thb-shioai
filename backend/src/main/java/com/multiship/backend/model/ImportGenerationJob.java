package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One label-generation run for an import, executed by a background worker
 * (see V57). Progress, the Cancel request and the outcome live here rather
 * than in JVM memory, so a run survives a restart and any server can report
 * on or cancel it.
 */
@Entity
@Table(name = "import_generation_job")
@Getter
@Setter
@NoArgsConstructor
public class ImportGenerationJob {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_batch_id", nullable = false)
    private Long importBatchId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    /** Requester's tenant scope at enqueue time; null for a platform operator. */
    @Column(name = "requested_scope", length = 60)
    private String requestedScope;

    @Column(name = "use_platform_account", nullable = false)
    private boolean usePlatformAccount;

    @Column(name = "progress_done", nullable = false)
    private int progressDone;

    @Column(name = "progress_total", nullable = false)
    private int progressTotal;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "worker_id", length = 120)
    private String workerId;

    @Column(name = "result_status", length = 30)
    private String resultStatus;

    @Column(name = "result_message", length = 1000)
    private String resultMessage;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public boolean isActive() {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }
}
