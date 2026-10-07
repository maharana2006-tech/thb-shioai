package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** V132 — one DTC scheduler run, shown in the page's run history. */
@Entity
@Table(name = "dtc_scheduler_run")
@Data
public class DtcSchedulerRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_key", nullable = false, length = 40)
    private String jobKey;

    /** SCHEDULE | MANUAL */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType;

    @Column(name = "triggered_by", length = 200)
    private String triggeredBy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** RUNNING | SUCCESS | FAILED | SKIPPED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "duration_ms")
    private Long durationMs;
}
