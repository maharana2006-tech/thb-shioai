package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

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
 *
 * <p>PR-G3b adds {@link #bySource} - a breakdown of the queue by the
 * source_type column (bulk / import / manual / MPS split / legacy
 * UNKNOWN) so ops can answer "which surface caused the current spike?".
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

    /**
     * PR-G3b - breakdown by {@code source_type}. Keys are the enum
     * literal ({@code BULK_OPERATOR} / {@code IMPORT_OPERATOR} /
     * {@code IMPORT_BACKGROUND} / {@code MPS_PIECE} / {@code MANUAL})
     * or the sentinel string {@code UNKNOWN} for pre-G3b rows without
     * a source stamp. Empty map when the queue has nothing QUEUED (the
     * repo's GROUP BY only surfaces sources with at least one QUEUED
     * row); FE renders "no queue activity" in that state.
     */
    private Map<String, PerSourceStats> bySource;

    /**
     * PR-G3b - per-source status breakdown. QUEUED depth is the primary
     * ranking column on the dashboard's "by source" panel; the other
     * status counts drive the drill-down chip that reveals whole-source
     * throughput (how many DONE + FAILED this source has posted).
     *
     * <p>All counts are non-negative platform-wide totals for the
     * source (not scoped to the current tenant). Ops looking at the
     * dashboard care about "where did the platform spike come from?",
     * not their own tenant slice.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PerSourceStats {
        /** Enum literal or the sentinel {@code "UNKNOWN"}. */
        private String sourceType;
        /** QUEUED rows waiting for the processor to pick them up. */
        private long queuedDepth;
        /** PROCESSING rows in-flight to USPS right now. */
        private long processingCount;
        /** DONE rows persisted with a tracking number. */
        private long doneCount;
        /** FAILED rows exhausted after retries. */
        private long failedCount;
        /** CANCELLED rows (cancel-cascade + admin-driven cancel). */
        private long cancelledCount;
    }
}
