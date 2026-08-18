package com.multiship.backend.service.retention;

import com.multiship.backend.config.RetentionProperties;
import com.multiship.backend.config.RetentionProperties.TableRetention;
import com.multiship.backend.repository.AuditLogRepository;
import com.multiship.backend.repository.ExternalWebhookDeliveryRepository;
import com.multiship.backend.repository.GeneratedReportRepository;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit R2 #330 + #335 + #356 + #362 — verifies the nightly retention
 * job dispatches to the four repositories with sensible cutoffs and
 * respects the {@code days == 0 = skip this phase} config knob.
 */
class BlobRetentionSchedulerTest {

    private ExternalWebhookDeliveryRepository webhookDeliveryRepo;
    private AuditLogRepository auditLogRepo;
    private GeneratedReportRepository generatedReportRepo;
    private ImportBatchRepository importBatchRepo;
    private RetentionProperties props;
    private BlobRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        webhookDeliveryRepo = mock(ExternalWebhookDeliveryRepository.class);
        auditLogRepo = mock(AuditLogRepository.class);
        generatedReportRepo = mock(GeneratedReportRepository.class);
        importBatchRepo = mock(ImportBatchRepository.class);
        props = new RetentionProperties();  // defaults per class
        scheduler = new BlobRetentionScheduler(
                webhookDeliveryRepo, auditLogRepo, generatedReportRepo, importBatchRepo, props);
    }

    @Test
    void runNightlyRetention_defaults_invokesAllFourTablesWithBothPhases() {
        // All 4 tables have both blobDays > 0 and rowDays > 0 in the
        // defaults, so we expect one call to each per repo per phase.
        when(webhookDeliveryRepo.nullifyPayloadOlderThan(any())).thenReturn(3);
        when(webhookDeliveryRepo.deleteRowsOlderThan(any())).thenReturn(1);
        when(auditLogRepo.nullifyChangesOlderThan(any())).thenReturn(5);
        when(auditLogRepo.deleteRowsOlderThan(any())).thenReturn(2);
        when(generatedReportRepo.nullifyCsvBytesOlderThan(any())).thenReturn(1);
        when(generatedReportRepo.deleteRowsOlderThan(any())).thenReturn(0);
        when(importBatchRepo.nullifyRowsJsonOlderThan(any())).thenReturn(2);
        when(importBatchRepo.deleteRowsOlderThan(any())).thenReturn(0);

        scheduler.runNightlyRetention();

        verify(webhookDeliveryRepo, times(1)).nullifyPayloadOlderThan(any(LocalDateTime.class));
        verify(webhookDeliveryRepo, times(1)).deleteRowsOlderThan(any(LocalDateTime.class));
        verify(auditLogRepo, times(1)).nullifyChangesOlderThan(any(LocalDateTime.class));
        verify(auditLogRepo, times(1)).deleteRowsOlderThan(any(LocalDateTime.class));
        verify(generatedReportRepo, times(1)).nullifyCsvBytesOlderThan(any(LocalDateTime.class));
        verify(generatedReportRepo, times(1)).deleteRowsOlderThan(any(LocalDateTime.class));
        verify(importBatchRepo, times(1)).nullifyRowsJsonOlderThan(any(LocalDateTime.class));
        verify(importBatchRepo, times(1)).deleteRowsOlderThan(any(LocalDateTime.class));
    }

    @Test
    void blobDaysZero_skipsNullPhaseOnly() {
        // Operators can opt out of the blob-null phase for a table if
        // they want to always keep the payload; hard-delete still runs.
        props.setWebhookDelivery(new TableRetention(0, 365));

        scheduler.runNightlyRetention();

        verify(webhookDeliveryRepo, never()).nullifyPayloadOlderThan(any());
        verify(webhookDeliveryRepo, times(1)).deleteRowsOlderThan(any());
    }

    @Test
    void rowDaysZero_skipsDeletePhaseOnly() {
        // Ops that want infinite audit-log rows can zero rowDays but
        // still reclaim the fat blob after a year.
        props.setAuditLog(new TableRetention(365, 0));

        scheduler.runNightlyRetention();

        verify(auditLogRepo, times(1)).nullifyChangesOlderThan(any());
        verify(auditLogRepo, never()).deleteRowsOlderThan(any());
    }

    @Test
    void bothZero_skipsBothPhases() {
        // Fully-disabled table: neither phase fires. Useful for tables
        // where retention is handled out-of-band (partition drop, etc).
        props.setImportBatch(new TableRetention(0, 0));

        scheduler.runNightlyRetention();

        verify(importBatchRepo, never()).nullifyRowsJsonOlderThan(any());
        verify(importBatchRepo, never()).deleteRowsOlderThan(any());
    }

    @Test
    void oneTableFailure_doesNotAbortOtherTables() {
        // If the webhook_delivery phase throws (schema drift, DB blip),
        // the other 3 tables still get processed. Fail-isolated by design.
        when(webhookDeliveryRepo.nullifyPayloadOlderThan(any()))
                .thenThrow(new RuntimeException("simulated DB blip"));

        scheduler.runNightlyRetention();

        // audit_log + generated_report + import_batch still ran.
        verify(auditLogRepo, times(1)).nullifyChangesOlderThan(any());
        verify(generatedReportRepo, times(1)).nullifyCsvBytesOlderThan(any());
        verify(importBatchRepo, times(1)).nullifyRowsJsonOlderThan(any());
    }
}
