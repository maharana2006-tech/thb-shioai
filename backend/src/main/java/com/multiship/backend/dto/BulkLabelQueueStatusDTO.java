package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PR-F1 Agent-2 — envelope surfaced to the bulk-label UI when the active
 * provider is {@code USPS_DIRECT} so operators can see queue depth
 * without leaving the bulk-label modal.
 *
 * <p>Populated by {@code BulkLabelServiceImpl.submit(...)} when it
 * routes any per-order request through
 * {@code UspsLabelQueueService.enqueue}. Empty {@link #queuedItemIds}
 * means the batch had no USPS shipments (all carriers stayed sync);
 * the FE hides the queue badge in that case.
 *
 * <p>Real-time depth + estimated wait come from
 * {@link UspsLabelQueueMetricsDTO} which the FE polls separately (30s
 * refresh from {@code BulkLabelQueueBadge}); this envelope only carries
 * the immediately-known submission-time facts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkLabelQueueStatusDTO {

    /** True when {@code USPS_PROVIDER=USPS_DIRECT} and at least one order
     *  in the batch resolved to a USPS carrier. */
    private boolean uspsQueueActive;

    /** How many orders from this batch actually got enqueued (excludes
     *  non-USPS carriers and skipped/failed orders). */
    private int queuedCount;

    /** Queue item ids returned by
     *  {@code UspsLabelQueueService.enqueue}, in submission order. Given
     *  to the FE so it could theoretically cancel individual items via
     *  {@code DELETE /admin/usps-direct/queue/items/{id}} — the bulk
     *  cancel path is a separate ergonomics feature. */
    private List<Long> queuedItemIds;

    /** ISO-8601 UTC of the projected start for the LAST queued item.
     *  Null when nothing was queued. FE renders this alongside the
     *  live queue depth in the warning banner. */
    private String estimatedLastStartAt;

    /** Snapshot of the queue at submission time (post-enqueue). Null on
     *  the sync path (uspsQueueActive=false). */
    private UspsLabelQueueMetricsDTO metricsSnapshot;
}
