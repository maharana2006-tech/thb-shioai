package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;

import java.time.LocalDateTime;
import java.util.List;

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
 *
 * <p>PR-F2 adds {@link #enqueueMps(EnqueueMpsRequest)} for the
 * "scenario A" MPS explosion: ONE order with N packages becomes N
 * queue rows sharing a {@code parent_order_no} so the admin surface
 * can aggregate progress across the pieces.
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
     * PR-F2 - Enqueue N pieces of ONE MPS order in a single transaction.
     * Every persisted row shares {@code parentOrderNo} so
     * {@code /mps-progress/{orderNo}} can GROUP BY to render aggregate
     * status; each row carries its {@code sequenceNumber} so the
     * processor + admin list preserve the operator's 1..N piece order.
     *
     * <p>Rejects individual pieces with duplicate shipmentIds inside the
     * batch (fail-fast: bad caller-supplied ids); a duplicate against a
     * pre-existing queue row rolls back the whole batch via the DB
     * UNIQUE(shipment_id) constraint - callers see
     * {@link IllegalStateException} identically to
     * {@link #enqueue(EnqueueRequest)}.
     *
     * <p>Estimated completion assumes the platform hourly cap (55/hr
     * default) and the batch's own size: "last piece" starts
     * {@code (N / cap)} hours from now on a fresh queue and finishes
     * shortly after (avg processing time is ~seconds per label).
     */
    EnqueueMpsResult enqueueMps(EnqueueMpsRequest request);

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

    // ================================================================
    // PR-F2 - MPS batch enqueue
    // ================================================================

    /**
     * PR-F2 - MPS batch enqueue request.
     *
     * @param tenantCode      Non-blank tenant / client code (applied to
     *                        every persisted row).
     * @param parentOrderNo   Non-null MPS parent order number. Stored on
     *                        every row so aggregation queries can GROUP BY.
     * @param pieces          Non-empty list of one entry per package.
     *                        Each entry supplies a distinct
     *                        {@code shipmentId} + 1-based
     *                        {@code sequenceNumber}. Duplicate shipmentIds
     *                        inside the batch throw IAE at validation time.
     * @param priority        Lower = more urgent. Applied uniformly across
     *                        every piece (bulk-triggered MPS runs at equal
     *                        priority so the processor's FIFO tie-break
     *                        preserves piece ordering).
     */
    record EnqueueMpsRequest(String tenantCode, Long parentOrderNo,
                              List<PieceRequest> pieces, int priority) {
        /**
         * One piece in an MPS enqueue.
         *
         * @param shipmentId       Non-null unique shipmentId for this
         *                         piece. Callers building this from an
         *                         {@code Order} + package sequence should
         *                         use a synthetic key that cannot collide
         *                         with real orderNos (the bulk-label wire-in
         *                         uses {@code -(parentOrderNo * 100_000 + seq)}
         *                         to guarantee distinct-from-orderNo).
         * @param sequenceNumber   1-based position within the MPS parent.
         *                         Must be strictly positive.
         */
        public record PieceRequest(Long shipmentId, int sequenceNumber) {}
    }

    /**
     * PR-F2 - MPS batch enqueue result.
     *
     * @param parentOrderNo                Echo of the parent order number
     *                                     the caller submitted.
     * @param enqueuedCount                Number of rows persisted (equal
     *                                     to {@code pieces.size()} on
     *                                     success).
     * @param estimatedFirstStartAt        Wall-clock timestamp the FIRST
     *                                     piece is expected to start
     *                                     processing.
     * @param estimatedLastCompleteAt      Wall-clock timestamp the LAST
     *                                     piece is expected to complete
     *                                     ({@code firstStart + N/cap hours}).
     */
    record EnqueueMpsResult(Long parentOrderNo, int enqueuedCount,
                             LocalDateTime estimatedFirstStartAt,
                             LocalDateTime estimatedLastCompleteAt) {}
}
