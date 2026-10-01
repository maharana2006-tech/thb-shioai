package com.multiship.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Aggregate over one DTC batch (all totes/orders sharing tenant + batch_id),
 * for the Dtcal-style batch-summary row and the generate-progress poll.
 */
public record DtcBatchStats(
        String tenantId,
        BigDecimal batchId,
        Long totalLines,
        BigDecimal startOrderNo,
        BigDecimal endOrderNo,
        String firstTote,
        String lastTote,
        String shipDate,
        Long generatedCount,
        Long failedCount,
        Long queuedCount,
        Long pendingCount,
        LocalDateTime lastSyncedAt) {

    /** Screenshot's Batch Status: COMPLETE when nothing is left to generate. */
    public String batchStatus() {
        long open = pendingCount + queuedCount;
        return open == 0 ? "COMPLETE" : "OPEN";
    }

    /** Screenshot's Label Status for the batch row. */
    public String labelStatus() {
        if (totalLines == null || totalLines == 0) return "NONE";
        if (generatedCount + queuedCount >= totalLines) return "GENERATED";
        if (generatedCount + queuedCount + failedCount == 0) return "PENDING";
        return "PARTIAL";
    }
}
