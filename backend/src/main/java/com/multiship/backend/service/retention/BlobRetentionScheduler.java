package com.multiship.backend.service.retention;

import com.multiship.backend.config.RetentionProperties;
import com.multiship.backend.config.RetentionProperties.TableRetention;
import com.multiship.backend.repository.AuditLogRepository;
import com.multiship.backend.repository.ExternalWebhookDeliveryRepository;
import com.multiship.backend.repository.GeneratedReportRepository;
import com.multiship.backend.repository.ImportBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.IntSupplier;

/**
 * Audit R2 #330 + #335 + #356 + #362 — nightly retention across the
 * four unbounded-blob tables.
 *
 * <p>Runs at 03:15 UTC daily (arbitrary offset from other nightly jobs
 * like {@code ApiKeyExpiryWarner} at 02:07 to spread DB load). Each
 * table gets two independent phases:
 *
 * <ol>
 *   <li><b>Blob null</b>: reclaim disk without losing the audit / list
 *       row — {@code UPDATE ... SET blob = NULL WHERE ts < cutoff}.</li>
 *   <li><b>Hard delete</b>: remove the whole row once we're past even
 *       the metadata window — {@code DELETE FROM ... WHERE ts < cutoff}.</li>
 * </ol>
 *
 * <p>Each table's own {@link TableRetention} config controls the two
 * windows independently. Setting either value to {@code 0} skips that
 * phase for the table (e.g. keep audit-log rows forever but null
 * their blobs after a year).
 *
 * <p>Phases run inside their own transaction so a failure on one
 * table doesn't roll back the others. Row counts get logged so
 * ops can see the reclaim + spot anomalies (0-forever suggests the
 * config is broken; runaway thousands suggests upstream churn).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlobRetentionScheduler {

    private final ExternalWebhookDeliveryRepository webhookDeliveryRepo;
    private final AuditLogRepository auditLogRepo;
    private final GeneratedReportRepository generatedReportRepo;
    private final ImportBatchRepository importBatchRepo;
    private final RetentionProperties props;

    /**
     * Cron string is a Spring 6-second format: {@code sec min hour dom mon dow}.
     * Runs at 03:15:00 UTC every day (assumes JVM TZ = UTC in prod deploy).
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void runNightlyRetention() {
        LocalDateTime now = LocalDateTime.now();
        log.info("BlobRetention: starting nightly sweep at {}", now);

        runOne("webhook_delivery.payload_json", props.getWebhookDelivery(), now,
                () -> phase("null-blob", "webhook_delivery",
                        () -> webhookDeliveryRepo.nullifyPayloadOlderThan(
                                now.minusDays(props.getWebhookDelivery().getBlobDays()))),
                () -> phase("delete-row", "webhook_delivery",
                        () -> webhookDeliveryRepo.deleteRowsOlderThan(
                                now.minusDays(props.getWebhookDelivery().getRowDays()))));

        runOne("audit_log.changes", props.getAuditLog(), now,
                () -> phase("null-blob", "audit_log",
                        () -> auditLogRepo.nullifyChangesOlderThan(
                                now.minusDays(props.getAuditLog().getBlobDays()))),
                () -> phase("delete-row", "audit_log",
                        () -> auditLogRepo.deleteRowsOlderThan(
                                now.minusDays(props.getAuditLog().getRowDays()))));

        runOne("generated_report.csv_bytes", props.getGeneratedReport(), now,
                () -> phase("null-blob", "generated_report",
                        () -> generatedReportRepo.nullifyCsvBytesOlderThan(
                                now.minusDays(props.getGeneratedReport().getBlobDays()))),
                () -> phase("delete-row", "generated_report",
                        () -> generatedReportRepo.deleteRowsOlderThan(
                                now.minusDays(props.getGeneratedReport().getRowDays()))));

        runOne("import_batch.rows_json", props.getImportBatch(), now,
                () -> phase("null-blob", "import_batch",
                        () -> importBatchRepo.nullifyRowsJsonOlderThan(
                                now.minusDays(props.getImportBatch().getBlobDays()))),
                () -> phase("delete-row", "import_batch",
                        () -> importBatchRepo.deleteRowsOlderThan(
                                now.minusDays(props.getImportBatch().getRowDays()))));

        log.info("BlobRetention: nightly sweep complete");
    }

    /**
     * Runs both phases for one table, skipping either when its config
     * days == 0. Ordering matters: null-blob FIRST (frees disk on rows
     * we're going to keep), then hard-delete SECOND (only touches rows
     * older than the blob window anyway — the two overlap harmlessly).
     */
    private static void runOne(String label, TableRetention cfg, LocalDateTime now,
                                Runnable nullPhase, Runnable deletePhase) {
        if (cfg.getBlobDays() > 0) {
            try { nullPhase.run(); }
            catch (Exception ex) { log.warn("BlobRetention null-blob phase for {} failed: {}", label, ex.getMessage()); }
        }
        if (cfg.getRowDays() > 0) {
            try { deletePhase.run(); }
            catch (Exception ex) { log.warn("BlobRetention delete-row phase for {} failed: {}", label, ex.getMessage()); }
        }
    }

    /**
     * Wraps the actual UPDATE / DELETE in a transaction so partial
     * batches don't leak, logs the row count, and re-throws so the
     * caller's try/catch surfaces the failure.
     */
    @Transactional
    protected int phase(String phase, String table, IntSupplier op) {
        int count = op.getAsInt();
        if (count > 0) {
            log.info("BlobRetention: {} on {} reclaimed {} row(s)", phase, table, count);
        }
        return count;
    }
}
