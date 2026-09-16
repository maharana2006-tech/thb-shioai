package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Backpressure metrics for the USPS_DIRECT label queue. Returned by
 * {@code UspsLabelQueueAdminController.GET /metrics} + used by the
 * FE (via Agent 2's wiring) to render the "label queued - est.
 * completion in X hours" toast.
 *
 * <p>All rates + durations are computed platform-wide by default. Pass
 * {@code ?tenant=CODE} to {@code /metrics} to get a per-tenant scoped
 * shape (only the {@link #tenantCode} field populated on the response).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsLabelQueueMetricsDTO {

    /**
     * Present iff this metrics envelope is scoped to a single tenant.
     * {@code null} on the platform-wide response.
     */
    private String tenantCode;

    /** Snapshot moment - server clock at build time. */
    private LocalDateTime asOf;

    // ============================================================
    // Depth counts
    // ============================================================

    /** Rows in {@code QUEUED}. */
    private long queuedDepth;

    /** Rows in {@code PROCESSING}. */
    private long processingCount;

    /** Rows in {@code DONE} (all-time, capped where cheap). */
    private long doneCount;

    /** Rows in {@code FAILED}. */
    private long failedCount;

    /** Rows in {@code CANCELLED}. */
    private long cancelledCount;

    // ============================================================
    // Pace + throughput
    // ============================================================

    /**
     * Age of the oldest {@code QUEUED} row, in seconds. Zero when the
     * queue is empty. Rendered as "oldest queued: 12 min" on the admin
     * dashboard.
     */
    private long oldestQueuedAgeSeconds;

    /**
     * Average (started_at -&gt; completed_at) over the last N completions
     * (default N = 100), in milliseconds. Zero when we have no data yet.
     */
    private long averageProcessingTimeMs;

    /**
     * Actual {@code DONE} count during the last rolling hour. Cross-
     * check against {@link #configuredHourlyCap} - if the two diverge
     * badly, either the callback is slower than expected or something
     * upstream is choking the processor.
     */
    private long currentHourlyPace;

    /**
     * Platform ceiling from {@code usps.direct.queue.hourly-cap}
     * (default 55 - a safety margin under USPS' documented 60/hour
     * limit for the platform OAuth app).
     */
    private long configuredHourlyCap;

    // ============================================================
    // Backpressure - what an enqueue right now would see
    // ============================================================

    /**
     * Estimated wall-clock wait, in seconds, for a NEW enqueue landing
     * right now. Platform-wide: {@code queuedDepth / hourly-cap}. When
     * {@link #tenantCode} is set, refined by the tenant's fair-share
     * slice of the platform pace.
     */
    private long estimatedWaitSeconds;

    /**
     * Server-computed "you can expect it to start at" wall-clock ts.
     * FE renders as "est. completion by 4:23 PM".
     */
    private LocalDateTime estimatedStartAt;

    // ============================================================
    // Per-tenant breakdown (platform-wide response only)
    // ============================================================

    /**
     * On the platform-wide response: one entry per tenant that
     * currently owns at least one {@code QUEUED} row, sorted by depth
     * descending so the loudest tenants surface first. {@code null} or
     * empty on the tenant-scoped response.
     */
    private List<TenantDepth> perTenantDepth;

    /** Compact per-tenant queue-depth entry for {@link #perTenantDepth}. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantDepth {
        private String tenantCode;
        private long queuedDepth;
    }
}
