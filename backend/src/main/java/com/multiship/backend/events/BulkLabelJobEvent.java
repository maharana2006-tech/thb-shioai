package com.multiship.backend.events;

/**
 * State change on a {@code bulk_label_jobs} row. Published by
 * {@link com.multiship.backend.service.BulkLabelServiceImpl} on:
 * <ul>
 *   <li>Job persisted (PENDING → recorded) — {@code eventType="job-created"}</li>
 *   <li>Worker fan-out started (PENDING → RUNNING) — {@code eventType="job-updated"}</li>
 *   <li>Progress tick (successfulCount or failedCount changed) — {@code eventType="job-progress"}</li>
 *   <li>Terminal state reached (COMPLETED / FAILED / CANCELLED) — {@code eventType="job-updated"}</li>
 *   <li>Cancellation requested — {@code eventType="job-cancel-requested"}</li>
 * </ul>
 *
 * <p>The FE consumer (BulkLabelModal via SSE) uses {@code eventType}
 * to decide whether to refetch full state or just update in-place. All
 * mutable counters are included in the payload so simple progress
 * updates don't require an additional HTTP round-trip.
 *
 * <p>{@code tenant} is the tenant/client scope resolved at publish
 * time. Null on rare platform-level events.
 */
public record BulkLabelJobEvent(
        String eventType,
        String tenant,
        Long jobId,
        String status,
        int totalCount,
        int successfulCount,
        int failedCount,
        boolean downloadable
) implements AppEvent {

    /** All bulk-label events live on this Redis channel. */
    public static final String TOPIC = "bulk-labels";

    @Override
    public String topic() {
        return TOPIC;
    }
}
