package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 37 — bulk label generation job. Tracks a batched request to
 * generate labels for N orders in parallel, along with counters, a
 * zipped-PDFs result, and status transitions.
 */
@Entity
@Table(name = "bulk_label_jobs")
@Data
public class BulkLabelJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Comma-separated order numbers to process. Serialised as-is to
     *  avoid pulling in a JSON column type; N is small (< a few hundred). */
    @Column(name = "order_numbers", nullable = false, columnDefinition = "text")
    private String orderNumbers;

    /** Who submitted the job (username from Spring Security). */
    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    /** PENDING → RUNNING → COMPLETED | FAILED.
     *  FAILED = the job itself blew up (executor down, DB write failed);
     *  per-order failures increment {@link #failed} but keep the job
     *  status at COMPLETED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "total_count", nullable = false)
    private int totalCount = 0;

    @Column(name = "successful_count", nullable = false)
    private int successfulCount = 0;

    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    /**
     * Base64-encoded ZIP of every successful label's PDF. Populated on
     * COMPLETED transition. Text column so JPA can persist it without
     * a BLOB column type; ~150 KB of base64 per 50 labels which
     * comfortably fits.
     */
    @Column(name = "result_zip_base64", columnDefinition = "text")
    private String resultZipBase64;

    /**
     * Human-readable failure summary if any orders failed — one line
     * per order number and reason, or a global error message for
     * FAILED jobs.
     */
    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
