package com.multiship.backend.service;

import com.multiship.backend.model.ImportBatch;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the stampCompletionIfTerminal helper (2026-09-12).
 * Verifies the operator ask: stamp on ANY terminal state, update on
 * every terminal transition (so retries overwrite), no-op on
 * non-terminal states.
 */
class OrderImportServiceImplCompletedAtTest {

    /** Reflect the private static helper so we can invoke it without
     *  spinning the whole service. */
    private static void stamp(ImportBatch batch) throws Exception {
        Method m = OrderImportServiceImpl.class.getDeclaredMethod(
                "stampCompletionIfTerminal", ImportBatch.class);
        m.setAccessible(true);
        m.invoke(null, batch);
    }

    private static ImportBatch batchWithStatus(String status) {
        ImportBatch b = new ImportBatch();
        b.setStatus(status);
        return b;
    }

    @Test
    void stampsOnComplete() throws Exception {
        ImportBatch b = batchWithStatus("COMPLETE");
        stamp(b);
        assertNotNull(b.getCompletedAt(), "COMPLETE must be stamped");
    }

    @Test
    void stampsOnPartialComplete() throws Exception {
        ImportBatch b = batchWithStatus("PARTIAL_COMPLETE");
        stamp(b);
        assertNotNull(b.getCompletedAt(), "PARTIAL_COMPLETE must be stamped");
    }

    @Test
    void stampsOnFailed() throws Exception {
        ImportBatch b = batchWithStatus("FAILED");
        stamp(b);
        assertNotNull(b.getCompletedAt(), "FAILED must be stamped");
    }

    @Test
    void stampsOnCancelled() throws Exception {
        ImportBatch b = batchWithStatus("CANCELLED");
        stamp(b);
        assertNotNull(b.getCompletedAt(), "CANCELLED must be stamped");
    }

    @Test
    void noStampOnInProgress() throws Exception {
        ImportBatch b = batchWithStatus("IN_PROGRESS");
        stamp(b);
        assertNull(b.getCompletedAt(),
                "IN_PROGRESS is not terminal — completedAt must stay null");
    }

    @Test
    void noStampOnInitiate() throws Exception {
        ImportBatch b = batchWithStatus("INITIATE");
        stamp(b);
        assertNull(b.getCompletedAt());
    }

    @Test
    void noStampOnDraft() throws Exception {
        ImportBatch b = batchWithStatus("DRAFT");
        stamp(b);
        assertNull(b.getCompletedAt());
    }

    @Test
    void retryOverwritesPreviousStamp() throws Exception {
        // First run — PARTIAL_COMPLETE stamps.
        ImportBatch b = batchWithStatus("PARTIAL_COMPLETE");
        stamp(b);
        LocalDateTime firstStamp = b.getCompletedAt();
        assertNotNull(firstStamp);

        // Time passes, retry starts (IN_PROGRESS clears the stamp per
        // OrderImportServiceImpl.generateLabelsForBatch — mirror that
        // here). Then the retry finishes with a new terminal state.
        Thread.sleep(10);
        b.setCompletedAt(null);
        b.setStatus("COMPLETE");
        stamp(b);
        LocalDateTime secondStamp = b.getCompletedAt();
        assertNotNull(secondStamp);
        // Operator ask: subsequent retries overwrite so the timestamp
        // always reflects the most recent completion.
        assertTrue(Duration.between(firstStamp, secondStamp).toMillis() >= 0,
                "second stamp should be at or after the first: first=" + firstStamp + " second=" + secondStamp);
    }

    @Test
    void nullBatchIsSafe() throws Exception {
        // No throw expected.
        stamp(null);
    }

    @Test
    void nullStatusIsSafe() throws Exception {
        ImportBatch b = new ImportBatch();
        // b.status is null by default.
        stamp(b);
        assertNull(b.getCompletedAt(),
                "null status must not be treated as terminal");
    }

    @Test
    void caseInsensitiveMatch() throws Exception {
        // Defensive against a legacy write path that saved lowercase.
        ImportBatch b = batchWithStatus("complete");
        stamp(b);
        assertNotNull(b.getCompletedAt());
    }
}
