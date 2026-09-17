package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PR-G3b - one recorded silent-fallback event surfaced by
 * {@code GET /admin/usps-direct/dashboard/fallback-alerts}.
 *
 * <p>PR-G3a landed the WARN-level log for background-worker USPS_DIRECT
 * paths that silently fall through to the sync connector call (audit
 * finding M-B3). Logs alone don't reach the ops dashboard, so PR-G3b
 * layers an in-memory ring buffer on top: every WARN-level fallback
 * also records an alert here so the FE can render a banner without
 * anyone tailing the log file.
 *
 * <p>Not persisted to the DB - the alert queue is bounded (last 50 by
 * default) and losing history on a restart is acceptable (the log file
 * remains the durable record). Skipping DB persistence keeps V63 focus
 * on the source_type + import_batch_id columns which have hard schema
 * consumers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UspsFallbackAlertDTO {

    /** UTC timestamp the fallback fired. */
    private Instant occurredAt;

    /**
     * Import row / order number the fallback happened on. Nullable
     * when the fallback fired before an orderNo was minted (uncommon
     * but possible on early-abort code paths).
     */
    private Long orderNo;

    /**
     * Tenant / client code the fallback belonged to. Nullable when
     * the source path couldn't derive a tenant (worker context without
     * a resolvable scope). Ops can filter by tenant in the FE if the
     * value is non-null.
     */
    private String tenantCode;

    /**
     * Import batch id when the fallback fired inside an import context.
     * Null for manual / bulk-label fallbacks. Enables ops to click
     * straight through to the batch's Data History row from the alert.
     */
    private Long importBatchId;

    /**
     * Short human-facing reason. Free-text; concise ("routing service
     * returned SYNC for USPS carrier under USPS_DIRECT provider"). The
     * FE renders verbatim.
     */
    private String reason;

    /**
     * Source label so ops can filter the banner by "which surface
     * silent-fell-back?". Values match {@code UspsLabelQueueItem.SourceType}
     * ({@code IMPORT_BACKGROUND} / {@code MANUAL} / etc.) or the
     * sentinel {@code UNKNOWN}.
     */
    private String source;
}
