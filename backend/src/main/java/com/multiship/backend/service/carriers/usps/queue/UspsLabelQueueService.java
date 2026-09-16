package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;

import java.time.LocalDateTime;

/**
 * PR-F1 Agent-2 stub — real implementation lives on the Agent-1 branch
 * (queue core). Delete this file at merge time; the real interface has
 * the same package + signature and Agent-2 code compiles against it
 * unchanged.
 *
 * <p>Contract (from PR-F1 brief):
 * <ul>
 *   <li>{@link #enqueue} — persist a queue item, return the estimated
 *       start time from the fair scheduler.</li>
 *   <li>{@link #getMetrics} — platform-wide depth / processing counts.</li>
 *   <li>{@link #getMetricsForTenant} — per-tenant view for scoped badges.</li>
 *   <li>{@link #cancel} — cancel a pending queue item; no-op once RUNNING.</li>
 * </ul>
 */
public interface UspsLabelQueueService {

    EnqueueResult enqueue(EnqueueRequest request);

    UspsLabelQueueMetricsDTO getMetrics();

    UspsLabelQueueMetricsDTO getMetricsForTenant(String tenantCode);

    boolean cancel(Long queueItemId);

    record EnqueueRequest(String tenantCode, Long shipmentId, int priority) {}

    record EnqueueResult(Long queueItemId, LocalDateTime estimatedStartAt) {}
}
