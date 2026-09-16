package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR-F1 Agent-2 stub — the real DTO is Agent 1's responsibility (fair
 * scheduler + rate limiter surface fields). Delete this file at merge
 * time; the real class exposes at minimum:
 *
 * <ul>
 *   <li>{@code depth} — count of PENDING queue rows.</li>
 *   <li>{@code processing} — count of RUNNING queue rows.</li>
 *   <li>{@code estimatedWaitSeconds} — how long the last PENDING item
 *       is projected to wait, given the 55/hr rate cap.</li>
 *   <li>{@code totalPerHourCap} — the platform-wide cap (55). Exposed
 *       so the FE can render a warning banner without hardcoding.</li>
 * </ul>
 *
 * <p>Agent-2 code (badge + modal integration) only reads {@code depth}
 * and {@code estimatedWaitSeconds}; any real DTO that keeps those
 * accessors survives the merge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsLabelQueueMetricsDTO {

    /** PENDING queue rows (waiting to be picked up). */
    private long depth;

    /** RUNNING queue rows (actively hitting USPS right now). */
    private long processing;

    /** Projected wait for the tail of the queue at the current rate. */
    private long estimatedWaitSeconds;

    /** Platform-wide per-hour cap (55 by default; the FE surfaces this
     *  verbatim in the warning banner so the number stays authoritative
     *  on the backend). Nullable on tenant-scoped metrics. */
    private Integer totalPerHourCap;

    /** Tenant scope, or {@code null} for the platform-wide view. */
    private String tenantCode;
}
