package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;

import java.time.LocalDateTime;

/**
 * Public API for the USPS_DIRECT PR-F persistent label queue.
 *
 * <p>Contract for Agent 2's wiring class ({@code BulkLabelServiceImpl}
 * extension) and for the admin controller. See
 * {@code docs/usps-direct-integration.md} PR-F for design rationale.
 *
 * <p>USPS' label API is capped at ~60 req/hour per platform OAuth app;
 * this queue serialises label writes at 55/hour (safety margin) with
 * per-tenant fair-sharing so a burst from one tenant cannot starve
 * others.
 */
public interface UspsLabelQueueService {

    /**
     * Enqueue a label request. Returns the queue-item id + an
     * estimated wall-clock start time so the caller can surface a
     * "queued - est. completion in X hours" toast in the UI.
     *
     * @throws IllegalStateException when a live queue entry (status
     *         not in DONE/CANCELLED) already exists for
     *         {@code request.shipmentId()}. Callers detect the double-
     *         enqueue by catching this exception.
     */
    EnqueueResult enqueue(EnqueueRequest request);

    /**
     * Platform-wide metrics for the backpressure UX: total depth,
     * per-tenant depth, processing count, oldest queued age, average
     * processing time (last 100 completions), current hourly pace.
     */
    UspsLabelQueueMetricsDTO getMetrics();

    /**
     * Same shape as {@link #getMetrics()} but scoped to one tenant.
     * Estimated wait is refined by that tenant's fair-share slice of
     * the platform pace.
     */
    UspsLabelQueueMetricsDTO getMetricsForTenant(String tenantCode);

    /**
     * Cancel a queued item. Returns {@code true} when the row was
     * {@code QUEUED} and flipped to {@code CANCELLED}. Returns
     * {@code false} when the row is missing or in a terminal state
     * ({@code PROCESSING}, {@code DONE}, {@code FAILED}, or already
     * {@code CANCELLED}) - the row is left untouched in that case.
     */
    boolean cancel(Long queueItemId);

    /**
     * Enqueue request.
     *
     * @param tenantCode  Non-blank tenant / client code.
     * @param shipmentId  Non-null local shipment id (order_label_tracking.id
     *                    or shipment.id). Unique-constrained on the row.
     * @param priority    Lower = more urgent. Default via
     *                    {@code UspsLabelQueueServiceImpl.DEFAULT_PRIORITY}
     *                    when the caller doesn't care.
     */
    record EnqueueRequest(String tenantCode, Long shipmentId, int priority) {}

    /**
     * Enqueue response.
     *
     * @param queueItemId       Server-assigned queue-row id.
     * @param estimatedStartAt  Wall-clock timestamp the processor is
     *                          expected to claim this row.
     */
    record EnqueueResult(Long queueItemId, LocalDateTime estimatedStartAt) {}
}
