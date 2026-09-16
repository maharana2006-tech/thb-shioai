package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * USPS_DIRECT PR-F - one row per USPS label request pending / in-flight
 * / finished through the platform's persistent queue. See
 * {@code V61__usps_label_queue.sql} and {@code docs/usps-direct-integration.md}
 * PR-F for the full design.
 *
 * <p>The queue exists because USPS' label API is capped at ~60 req/hour
 * per platform OAuth app; a burst from a 1000-piece MPS shipment or a
 * 1000-order bulk-label run would exhaust that quota within seconds and
 * everyone on the platform would 429 for the rest of the hour. The
 * queue serialises label writes at 55/hour and per-tenant fair-shares
 * the slots so a big tenant cannot starve a small one.
 *
 * <p>{@link #shipmentId} is uniquely constrained so a second enqueue
 * for the same shipment (whether from an operator double-click or a
 * retry loop) is rejected at persist time - the caller sees a
 * DB-integrity exception surfaced as {@link IllegalStateException} in
 * the service layer.
 *
 * <p>PR-F2 - {@link #parentOrderNo} + {@link #sequenceNumber} are the
 * MPS ("one order = N label calls") aggregation keys. Non-MPS rows
 * leave both NULL; the admin surface aggregates progress via
 * {@code GROUP BY parent_order_no}.
 */
@Entity
@Table(name = "usps_label_queue",
        uniqueConstraints = @UniqueConstraint(name = "uk_usps_label_queue_shipment",
                columnNames = "shipment_id"),
        indexes = {
                @Index(name = "idx_usps_queue_status_tenant",
                        columnList = "status, tenant_code, priority, enqueued_at"),
                @Index(name = "idx_usps_queue_parent",
                        columnList = "parent_order_no")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsLabelQueueItem {

    /**
     * Row lifecycle. See {@code V61__usps_label_queue.sql} header for
     * the transitions the processor + service enforce.
     */
    public enum Status {
        QUEUED,
        PROCESSING,
        DONE,
        FAILED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant / client code owning this label request. */
    @Column(name = "tenant_code", nullable = false, length = 64)
    private String tenantCode;

    /** Local shipment identifier (order_label_tracking.id or shipment.id). Unique. */
    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    /**
     * Lower value = higher priority (POSIX-nice convention). Default
     * 100 via the migration; admin surface may set {@code <=10} to mark
     * a row urgent so it jumps the round-robin in
     * {@link com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueFairScheduler}.
     */
    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @CreationTimestamp
    @Column(name = "enqueued_at", nullable = false, updatable = false)
    private LocalDateTime enqueuedAt;

    /** Set when the processor claims the row (QUEUED -> PROCESSING). */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** Set when the callback returns (DONE / FAILED / CANCELLED). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** How many times the callback has thrown against this row. */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /** Last failure message (truncated to what the DB stores). */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Populated on DONE - USPS-assigned tracking number. */
    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    /**
     * PR-F2 - MPS parent order number. {@code NULL} on single-label
     * enqueue rows (the PR-F1 shape); populated when this row is one
     * piece of an N-piece MPS shipment fanned out by
     * {@code UspsMpsSplitterService}. The admin surface aggregates
     * progress across the N pieces via {@code GROUP BY parent_order_no}
     * so operators can see "order 12345: 240/1000 pieces done".
     */
    @Column(name = "parent_order_no")
    private Long parentOrderNo;

    /**
     * PR-F2 - 1-based position within the parent MPS order. {@code NULL}
     * on non-MPS rows. Preserves piece ordering when the processor
     * picks items (rows with the same priority + enqueued_at tie-break
     * on this so piece 1 lands before piece 2 in the FIFO drain).
     */
    @Column(name = "sequence_number")
    private Integer sequenceNumber;
}
