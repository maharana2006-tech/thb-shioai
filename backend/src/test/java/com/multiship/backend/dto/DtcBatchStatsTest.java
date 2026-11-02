package com.multiship.backend.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BUG-068 — a batch with no label at all must not read as PARTIAL. */
class DtcBatchStatsTest {

    private static DtcBatchStats stats(long total, long generated, long failed, long queued, long pending) {
        return new DtcBatchStats("ARHDEV", null, total, null, null, null, null, null,
                generated, failed, queued, pending, 0L, null);
    }

    @Test
    void allFailed_isFailed() {
        assertEquals("FAILED", stats(1, 0, 1, 0, 0).labelStatus());
        assertEquals("COMPLETE", stats(1, 0, 1, 0, 0).batchStatus());
    }

    @Test
    void mixedOutcomes_keepExistingStatuses() {
        assertEquals("PARTIAL", stats(2, 1, 1, 0, 0).labelStatus());
        assertEquals("GENERATED", stats(2, 2, 0, 0, 0).labelStatus());
        assertEquals("PENDING", stats(2, 0, 0, 0, 2).labelStatus());
        assertEquals("NONE", stats(0, 0, 0, 0, 0).labelStatus());
    }
}
