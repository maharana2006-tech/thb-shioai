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
 *
 * <p>PR-G3b - {@link #sourceType} + {@link #importBatchId} are the
 * cross-flow provenance fields. Every enqueue caller stamps a source
 * so the admin dashboard can attribute quota consumption to a specific
 * surface (bulk modal vs import operator vs import background vs
 * manual vs MPS split). {@link #importBatchId} is non-null iff the
 * enqueue originated inside an import batch and enables the cancel-
 * cascade path ({@code OrderImportService.cancelGeneration} calls
 * {@code uspsLabelQueueService.cancelPending(importBatchId)}).
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

    /**
     * PR-G3b - which surface caused this enqueue. Filled at enqueue
     * time by every caller of {@link com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService};
     * the admin dashboard groups + filters by this so ops can answer
     * "is this spike from a background import job or the manual modal?".
     *
     * <p>Values:
     * <ul>
     *   <li>{@link #BULK_OPERATOR} - bulk-label modal driven by an operator
     *       ({@code BulkLabelServiceImpl.processOneOrder}).</li>
     *   <li>{@link #IMPORT_OPERATOR} - CSV/XLSX import Generate Labels
     *       triggered inline by an operator (jobId==null in
     *       {@code OrderImportServiceImpl.commit}).</li>
     *   <li>{@link #IMPORT_BACKGROUND} - CSV/XLSX import Generate Labels
     *       running under a background worker (jobId!=null; see
     *       {@code ImportGenerationWorker}). Highest blast-radius origin.</li>
     *   <li>{@link #MPS_PIECE} - one piece of an MPS batch fanned out by
     *       {@code UspsMpsSplitterService} without a more specific parent
     *       source. When the parent enqueue also carries a specific origin
     *       (bulk vs import) the split pieces inherit it, so the dashboard
     *       attributes to the real triggering surface.</li>
     *   <li>{@link #MANUAL} - single-order manual /orders/manual-label or
     *       list-view Generate button
     *       ({@code CarrierServiceImpl.maybeRouteUspsDirect}).</li>
     * </ul>
     *
     * <p>Nullable at the DB layer for pre-G3b rows enqueued before the
     * migration landed; the dashboard treats NULL as "legacy / unknown"
     * so the backfill window doesn't skew percentages.
     */
    public enum SourceType {
        BULK_OPERATOR,
        IMPORT_OPERATOR,
        IMPORT_BACKGROUND,
        MPS_PIECE,
        MANUAL
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

    /**
     * PR-G3b - Which surface caused this enqueue. See {@link SourceType}
     * for the value set. Nullable for pre-G3b rows during the backfill
     * window; the admin dashboard treats NULL as "legacy / unknown" so
     * the aggregation doesn't skew percentages while old rows drain out.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 24)
    private SourceType sourceType;

    /**
     * PR-G3b - FK-analog to {@code import_batch.id} when this enqueue
     * originated inside an import batch (operator or background). Enables
     * the cancel-cascade path ({@code OrderImportService.cancelGeneration}
     * -&gt; {@code uspsLabelQueueService.cancelPending(importBatchId)}) and
     * the "which queue rows belong to import #N" admin drill-down.
     *
     * <p>NULL for BULK_OPERATOR / MANUAL / standalone MPS - those
     * enqueues never carry an import-batch identity. Not a formal FK
     * because the queue outlives the batch (a CANCELLED batch keeps its
     * DONE queue rows for auditing) and CASCADE DELETE would lose
     * tracking-number history.
     */
    @Column(name = "import_batch_id")
    private Long importBatchId;
}
