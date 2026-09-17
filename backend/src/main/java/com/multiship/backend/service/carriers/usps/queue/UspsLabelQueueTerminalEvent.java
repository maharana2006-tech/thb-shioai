package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;

/**
 * PR-G3b - Spring {@link org.springframework.context.ApplicationEvent}
 * marker published by {@link UspsLabelQueueProcessor} whenever a queue
 * row lands in a terminal status ({@link UspsLabelQueueItem.Status#DONE}
 * or {@link UspsLabelQueueItem.Status#FAILED}). Consumed by
 * {@link UspsQueueImportRowReconciler} to bridge queue completions back
 * to the originating import row's {@code generatedStatus} so the U5
 * "stuck at QUEUED_USPS" gap closes.
 *
 * <p>Deliberately NOT joining the sealed {@code AppEvent} bus - that
 * bus is for FE-visible SSE traffic (bulk labels, batch updates, void
 * failures). Queue-terminal events are backend-internal plumbing that
 * fire ~55/hr per platform (matching the queue cap) and never reach
 * the FE directly. Spring's plain {@code ApplicationEvent} is the
 * lower-overhead choice and doesn't require an AppEvent expansion PR.
 *
 * @param queueItemId    id of the queue row that finished. Non-null.
 * @param importBatchId  originating import batch when the enqueue came
 *                       from {@code OrderImportServiceImpl}; null for
 *                       BULK_OPERATOR / MANUAL / standalone MPS
 *                       enqueues. When null, the reconciler has nothing
 *                       to do and short-circuits.
 * @param orderNo        order number the queue row wrote a label for.
 *                       For single-label rows this is the raw shipmentId;
 *                       for MPS pieces this is {@code parentOrderNo}.
 *                       Nullable if resolution failed (defense in depth).
 * @param terminalStatus DONE or FAILED. The reconciler ignores every
 *                       other status (PROCESSING / QUEUED / CANCELLED)
 *                       to keep the event contract narrow.
 * @param trackingNumber USPS-assigned tracking on DONE; null on FAILED.
 * @param errorMessage   truncated failure text on FAILED; null on DONE.
 */
public record UspsLabelQueueTerminalEvent(
        Long queueItemId,
        Long importBatchId,
        Long orderNo,
        UspsLabelQueueItem.Status terminalStatus,
        String trackingNumber,
        String errorMessage) {
}
