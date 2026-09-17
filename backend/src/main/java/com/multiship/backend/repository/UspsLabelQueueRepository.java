package com.multiship.backend.repository;

import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.SourceType;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Data-access for {@link UspsLabelQueueItem}. See
 * {@code docs/usps-direct-integration.md} PR-F for the queue design.
 *
 * <p>Two hot paths are indexed via the composite
 * {@code (status, tenant_code, priority, enqueued_at)} index on the
 * table:
 * <ul>
 *   <li>{@link #findByStatusOrderByPriorityAscEnqueuedAtAsc(Status)}
 *       - the processor's pick-next call each tick.</li>
 *   <li>{@link #countByStatusAndTenantCode(Status, String)} - the
 *       per-tenant depth metric for backpressure UX.</li>
 * </ul>
 *
 * <p>PR-F2 adds MPS aggregation:
 * <ul>
 *   <li>{@link #findByParentOrderNoOrderBySequenceNumberAsc(Long)} -
 *       list every piece for a parent order (bounded by the max MPS
 *       size, ~1000).</li>
 *   <li>{@link #findStatusCountsByParentOrderNo(Long)} - the GROUP BY
 *       projection the {@code /mps-progress} endpoint aggregates in
 *       a single round-trip instead of iterating rows.</li>
 * </ul>
 *
 * <p>PR-F4 adds dashboard aggregation:
 * <ul>
 *   <li>{@link #findRetryCountsByHour(LocalDateTime)} - GROUP BY over
 *       {@code date_trunc('hour', enqueued_at)} for the per-hour
 *       retry / failure histogram on the admin dashboard.</li>
 * </ul>
 *
 * <p>PR-G3b adds source + import-batch access:
 * <ul>
 *   <li>{@link #findByImportBatchIdAndStatus(Long, Status)} - powers
 *       the cancel-cascade path from
 *       {@code OrderImportService.cancelGeneration} + the "queue rows
 *       for import #N" admin drill-down.</li>
 *   <li>{@link #countBySourceTypeAndStatus(SourceType, Status)} +
 *       {@link #findDepthBySource()} - drive the dashboard's by-source
 *       breakdown answering "which surface caused the current spike?".</li>
 * </ul>
 */
@Repository
public interface UspsLabelQueueRepository
        extends JpaRepository<UspsLabelQueueItem, Long> {

    /**
     * Lookup by shipment id - respects the unique constraint. Used by
     * {@code UspsLabelQueueService.enqueue()} to reject a duplicate
     * enqueue before it hits the DB integrity check.
     */
    Optional<UspsLabelQueueItem> findByShipmentId(Long shipmentId);

    /**
     * Pick-next order for the processor: priority ASC (lower = more
     * urgent), then enqueued_at ASC (FIFO within same priority). The
     * fair scheduler consumes this list and round-robins across tenants.
     */
    List<UspsLabelQueueItem> findByStatusOrderByPriorityAscEnqueuedAtAsc(Status status);

    /** Total rows in a given status - platform-wide depth metric. */
    long countByStatus(Status status);

    /** Per-tenant depth - {@link Status#QUEUED} for backpressure UX. */
    long countByStatusAndTenantCode(Status status, String tenantCode);

    /**
     * Rows completed since {@code cutoff}. The service uses this to
     * measure recent throughput (DONE-per-hour) for pace metrics.
     */
    List<UspsLabelQueueItem> findByStatusAndCompletedAtAfter(Status status, LocalDateTime cutoff);

    /**
     * GROUP BY projection for the /metrics endpoint - how many rows
     * each tenant currently has in {@code status}. Ordered by depth
     * DESC so the admin sees the loudest tenants first.
     */
    @Query(value = """
            SELECT tenant_code AS tenantCode,
                   COUNT(*)    AS depth
              FROM usps_label_queue
             WHERE status = :#{#status.name()}
          GROUP BY tenant_code
          ORDER BY depth DESC
            """, nativeQuery = true)
    List<TenantDepth> findQueueDepthByTenant(@Param("status") Status status);

    /**
     * Paginated list for the admin /items endpoint. Ordered by enqueued_at
     * DESC so the newest work is on top.
     */
    Page<UspsLabelQueueItem> findAllByOrderByEnqueuedAtDesc(Pageable pageable);

    // ================================================================
    // PR-F2 - MPS aggregation
    // ================================================================

    /**
     * PR-F2 - list every piece for an MPS parent order, ordered by
     * {@code sequence_number} ASC. Used by the {@code /mps-progress}
     * endpoint to emit the first N completed tracking numbers in
     * piece order. Bounded by the max MPS size (~1000 pieces per
     * order); no pagination needed.
     */
    List<UspsLabelQueueItem> findByParentOrderNoOrderBySequenceNumberAsc(Long parentOrderNo);

    /**
     * PR-F2 - GROUP BY projection over an MPS parent's rows. Returns
     * one entry per status the parent's pieces sit in (e.g. QUEUED=750,
     * PROCESSING=5, DONE=240, FAILED=3, CANCELLED=2). One round-trip
     * beats scanning 1000 rows per progress poll.
     *
     * <p>Uses JPQL (not native) so Hibernate maps the enum column back
     * to {@link Status} automatically - the constructor expression
     * bakes the projection type into the query so no manual mapper is
     * needed.
     */
    @Query("""
            SELECT new com.multiship.backend.repository.UspsLabelQueueRepository$StatusCount(
                       i.status, count(i))
              FROM UspsLabelQueueItem i
             WHERE i.parentOrderNo = :parentOrderNo
          GROUP BY i.status
            """)
    List<StatusCount> findStatusCountsByParentOrderNo(@Param("parentOrderNo") Long parentOrderNo);

    // ================================================================
    // PR-F4 - dashboard aggregation
    // ================================================================

    /**
     * PR-F4 - per-hour retry / failure histogram feeding the admin
     * dashboard's stacked-bar chart. One row per {@code date_trunc('hour',
     * enqueued_at)} bucket with at least one row in the window.
     *
     * <p>Columns:
     * <ul>
     *   <li>{@code hourStart} - {@code date_trunc('hour', enqueued_at)}
     *       cast to {@code TIMESTAMP} (LocalDateTime in JVM land).</li>
     *   <li>{@code attempts} - rows with {@code retry_count = 0}
     *       (first-attempt enqueues, roughly "how many label requests
     *       hit us this hour").</li>
     *   <li>{@code retries} - {@code sum(retry_count)} across the
     *       hour's rows; captures the retry churn USPS instability
     *       drives.</li>
     *   <li>{@code failures} - rows in {@code FAILED} status right now.
     *       Snapshot; a later successful retry moves the row out of
     *       FAILED and it leaves this counter on the next query.</li>
     * </ul>
     *
     * <p>Native query (Postgres {@code date_trunc}) because JPQL has
     * no portable "truncate to hour" primitive. H2 in test mode
     * supports {@code date_trunc} via its Postgres-compat function,
     * so the same query runs against both the production DB and the
     * test containers used by IT suites.
     *
     * <p>Ordered by {@code hourStart} ASC so the FE can plot left to
     * right without a client-side sort.
     */
    @Query(value = """
            SELECT date_trunc('hour', enqueued_at) AS hourStart,
                   COUNT(*) FILTER (WHERE retry_count = 0) AS attempts,
                   COALESCE(SUM(retry_count), 0) AS retries,
                   COUNT(*) FILTER (WHERE status = 'FAILED') AS failures
              FROM usps_label_queue
             WHERE enqueued_at >= :startInstant
          GROUP BY 1
          ORDER BY 1 ASC
            """, nativeQuery = true)
    List<HourlyRetryBucket> findRetryCountsByHour(@Param("startInstant") LocalDateTime startInstant);

    // ================================================================
    // PR-G3b - source + import-batch access
    // ================================================================

    /**
     * PR-G3b - list every queue row belonging to a given import batch,
     * scoped to the caller-supplied status. Used by the cancel-cascade
     * path ({@code Status.QUEUED} filter) and by the admin drill-down
     * that walks a specific import's queue provenance.
     *
     * <p>Backed by the partial index {@code idx_usps_label_queue_import_batch
     * (import_batch_id, status) WHERE import_batch_id IS NOT NULL}
     * added in V63 so the lookup stays O(k) in the number of matching
     * rows.
     */
    List<UspsLabelQueueItem> findByImportBatchIdAndStatus(Long importBatchId, Status status);

    /**
     * PR-G3b - count queue rows in the given ({@code sourceType},
     * {@code status}) tuple. Called once per (source, status) cell by
     * the dashboard's by-source breakdown. Deliberately unindexed on
     * status here because the partial
     * {@code idx_usps_label_queue_source_type (source_type, status)
     * WHERE source_type IS NOT NULL} covers the read path already.
     */
    long countBySourceTypeAndStatus(SourceType sourceType, Status status);

    /**
     * PR-G3b - GROUP BY {@code source_type} over the currently QUEUED
     * rows. Feeds the dashboard's "queue depth by source" ranking so
     * ops can see at a glance which surface caused the current spike.
     *
     * <p>NULL source_type rows are collapsed into a single "UNKNOWN"
     * bucket via the coalesce so a partial backfill window doesn't
     * split the aggregation across nine tiny groups.
     *
     * <p>Ordered by depth DESC so the loudest source appears first.
     */
    @Query(value = """
            SELECT COALESCE(source_type, 'UNKNOWN') AS sourceType,
                   COUNT(*)                          AS depth
              FROM usps_label_queue
             WHERE status = 'QUEUED'
          GROUP BY COALESCE(source_type, 'UNKNOWN')
          ORDER BY depth DESC
            """, nativeQuery = true)
    List<SourceDepth> findQueueDepthBySource();

    /**
     * Native GROUP BY row shape - Spring Data will project a native
     * result into this interface automatically. Kept intentionally
     * flat + primitive so no manual mapper is needed.
     */
    interface TenantDepth {
        String getTenantCode();
        Long getDepth();
    }

    /**
     * PR-G3b - projection interface for {@link #findQueueDepthBySource()}.
     * {@code sourceType} is a plain String so the "UNKNOWN" coalesced
     * bucket doesn't have to be a real enum value.
     */
    interface SourceDepth {
        String getSourceType();
        Long getDepth();
    }

    /**
     * PR-F4 - projection interface for {@link #findRetryCountsByHour(LocalDateTime)}.
     * All fields nullable at the JDBC level - the mapper collapses
     * NULL to 0 in the service.
     */
    interface HourlyRetryBucket {
        LocalDateTime getHourStart();
        Long getAttempts();
        Long getRetries();
        Long getFailures();
    }

    /**
     * PR-F2 - GROUP BY projection for {@link #findStatusCountsByParentOrderNo(Long)}.
     * Constructor-expression target so JPQL can build instances directly.
     * Record because the row is immutable + trivially value-typed - no
     * setters required.
     */
    record StatusCount(Status status, long count) {}
}
