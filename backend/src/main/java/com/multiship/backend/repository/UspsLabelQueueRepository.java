package com.multiship.backend.repository;

import com.multiship.backend.model.UspsLabelQueueItem;
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

    /**
     * Native GROUP BY row shape - Spring Data will project a native
     * result into this interface automatically. Kept intentionally
     * flat + primitive so no manual mapper is needed.
     */
    interface TenantDepth {
        String getTenantCode();
        Long getDepth();
    }
}
