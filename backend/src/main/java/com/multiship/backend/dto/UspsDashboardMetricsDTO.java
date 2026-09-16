package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PR-F4 - composite dashboard payload for
 * {@code GET /admin/usps-direct/dashboard}. One round-trip fills the
 * entire USPS Direct admin dashboard (queue depth + tenant breakdown,
 * quota headroom, per-hour retry chart, void reconciliation rollup).
 *
 * <p>Individual sub-DTOs are also served from their own endpoints for
 * lightweight polling refreshes (e.g. the quota gauge polls
 * {@code /dashboard/quota-headroom} every 15-30s without pulling the
 * full composite). This composite exists so the initial dashboard load
 * is one call, not four.
 *
 * <p>Every sub-field is optional at the JSON level - a partial outage
 * (e.g. reconciliation query fails) drops just that sub-DTO rather
 * than 500ing the whole dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UspsDashboardMetricsDTO {

    /** Server clock at composite-build time. */
    private Instant generatedAt;

    /**
     * Existing PR-F1 queue metrics (platform-wide depth + tenant
     * breakdown + processing count + estimated wait). Reused verbatim
     * so callers already coded against {@code /queue/metrics} can
     * consume the same shape here.
     */
    private UspsLabelQueueMetricsDTO queue;

    /** Quota-bucket headroom snapshot. See {@link UspsQuotaHeadroomDTO}. */
    private UspsQuotaHeadroomDTO quota;

    /** Per-hour retry / failure histogram. See {@link UspsRetryBucketDTO}. */
    private UspsRetryBucketDTO retries;

    /** Void-reconciliation rollup. See {@link UspsReconciliationRollupDTO}. */
    private UspsReconciliationRollupDTO reconciliation;
}
