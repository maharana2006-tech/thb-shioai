package com.multiship.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit R2 #330 + #335 + #356 + #362 — retention windows for the four
 * unbounded-blob tables:
 *
 * <ul>
 *   <li>{@code webhook_delivery} — HMAC event payload JSON per attempt.
 *       Highest RPS: 100 events/min × 100 tenants = ~50k rows/day.</li>
 *   <li>{@code audit_log} — settings-write blob (before/after snapshot).
 *       Auditors want long history so hard-delete is generous.</li>
 *   <li>{@code generated_report} — CSV bytes per scheduled export.
 *       Per-row size can hit tens of MB (BILLING report).</li>
 *   <li>{@code import_batch} — bulk-import rows JSON, one row per
 *       operator upload.</li>
 * </ul>
 *
 * <p>Two knobs per table:
 * <ol>
 *   <li>{@code blobDays} — after this many days the heavy blob column
 *       is nulled (metadata row kept for audit / correlation).</li>
 *   <li>{@code rowDays} — after this many days the whole row is
 *       hard-deleted.</li>
 * </ol>
 *
 * <p>Sensible defaults picked per-table. Override any of them via env
 * (e.g. {@code RETENTION_WEBHOOKDELIVERY_BLOBDAYS=14}) or
 * {@code application.yml} for tighter policy in prod.
 *
 * <p>Set either value to {@code 0} to disable that phase for the table
 * (e.g. {@code auditLog.rowDays=0} to never hard-delete).
 */
@Data
@ConfigurationProperties(prefix = "retention")
public class RetentionProperties {

    private TableRetention webhookDelivery = new TableRetention(30, 365);
    private TableRetention auditLog        = new TableRetention(365, 2555);  // ~7 years
    private TableRetention generatedReport = new TableRetention(30, 90);
    private TableRetention importBatch     = new TableRetention(180, 730);   // ~2 years

    @Data
    public static class TableRetention {
        /** Days after which the heavy blob column is set to NULL.
         *  0 disables blob nulling. */
        private int blobDays;
        /** Days after which the row is hard-deleted.
         *  0 disables hard-delete. */
        private int rowDays;

        public TableRetention() {}
        public TableRetention(int blobDays, int rowDays) {
            this.blobDays = blobDays;
            this.rowDays = rowDays;
        }
    }
}
