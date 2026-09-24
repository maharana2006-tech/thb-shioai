package com.multiship.backend.integration;

import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.service.BulkBatchQueryService;
import com.multiship.backend.service.BulkBatchQueryService.Summary;
import com.multiship.backend.service.BulkBatchQueryService.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link BulkBatchQueryService#summary(View)} contract after the
 * 11-count-fanout → single aggregation rewrite. Preserved bit-for-bit:
 * <ul>
 *   <li>{@code statusCounts} contains only non-zero buckets, plus {@code ALL}</li>
 *   <li>{@code readyToGenerate} = INITIATE AND invalidRows == 0</li>
 *   <li>{@code needsFixes} = (DRAFT OR FAILED) OR invalidRows &gt; 0</li>
 *   <li>{@code completedThisWeek} = COMPLETE AND completedAt &gt;= now - 7d</li>
 * </ul>
 *
 * <p>Runs only under {@code INTEGRATION_TESTS=1} (inherited from
 * {@link AbstractIntegrationTest} — needs the real Postgres testcontainer).
 */
class BulkBatchQuerySummaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private BulkBatchQueryService service;
    @Autowired private ImportBatchRepository repository;

    @BeforeEach
    void clearBatches() {
        // Testcontainer Postgres is shared across the suite; other tests may
        // have left batches. Start clean so assertions are deterministic.
        repository.deleteAll();
    }

    @Test
    void summaryReturnsAggregatedCountsAcrossAllBuckets() {
        LocalDateTime now = LocalDateTime.now();
        // DRAFT — counted, contributes to needsFixes
        save(newBatch("DRAFT", 0, null, now));
        // INITIATE, invalidRows == 0 — counted, contributes to readyToGenerate
        save(newBatch("INITIATE", 0, null, now));
        // INITIATE, invalidRows > 0 — counted (INITIATE bucket++), contributes to needsFixes, NOT ready
        save(newBatch("INITIATE", 3, null, now));
        // IN_PROGRESS — counted, contributes to generating
        save(newBatch("IN_PROGRESS", 0, null, now));
        // COMPLETE this week — counted, contributes to completedThisWeek
        save(newBatch("COMPLETE", 0, now.minusDays(1), now));
        // COMPLETE > 7d ago — counted, does NOT contribute to completedThisWeek
        save(newBatch("COMPLETE", 0, now.minusDays(30), now));
        // FAILED — counted, contributes to needsFixes
        save(newBatch("FAILED", 0, null, now));

        Summary s = service.summary(View.FILE);

        // Total across all buckets
        assertEquals(7L, s.total());
        // Status counts: only non-zero buckets appear, plus ALL
        assertEquals(1L, s.statusCounts().get("DRAFT"));
        assertEquals(2L, s.statusCounts().get("INITIATE"));
        assertEquals(1L, s.statusCounts().get("IN_PROGRESS"));
        assertEquals(2L, s.statusCounts().get("COMPLETE"));
        assertEquals(1L, s.statusCounts().get("FAILED"));
        assertEquals(7L, s.statusCounts().get("ALL"));
        assertFalse(s.statusCounts().containsKey("CANCELLED"),
                "zero-count buckets must not appear (per prior contract)");
        assertFalse(s.statusCounts().containsKey("PARTIAL_COMPLETE"),
                "zero-count buckets must not appear (per prior contract)");
        // Derived counts
        assertEquals(1L, s.readyToGenerate(), "one INITIATE with invalidRows=0");
        assertEquals(1L, s.generating(), "one IN_PROGRESS");
        assertEquals(3L, s.needsFixes(), "DRAFT + FAILED + INITIATE-with-invalidRows>0");
        assertEquals(1L, s.completedThisWeek(), "only the recent COMPLETE, not the 30-day-old one");
    }

    @Test
    void summaryReturnsZeroesWhenNoBatches() {
        Summary s = service.summary(View.FILE);
        assertEquals(0L, s.total());
        assertEquals(1, s.statusCounts().size(), "only the ALL entry when empty");
        assertEquals(0L, s.statusCounts().get("ALL"));
        assertEquals(0L, s.readyToGenerate());
        assertEquals(0L, s.generating());
        assertEquals(0L, s.needsFixes());
        assertEquals(0L, s.completedThisWeek());
        assertTrue(s.creators().isEmpty());
    }

    private static ImportBatch newBatch(String status, int invalidRows,
                                        LocalDateTime completedAt, LocalDateTime now) {
        ImportBatch b = new ImportBatch();
        b.setStatus(status);
        b.setInvalidRows(invalidRows);
        b.setCompletedAt(completedAt);
        b.setCreatedAt(now);
        b.setCreatedBy("it-user");
        b.setFileName("it-" + status.toLowerCase() + ".csv");
        b.setTotalRows(10);
        b.setSavedRows(10 - invalidRows);
        b.setSource("BULK");          // FILE view excludes WMS/API
        b.setBillingMode("AUTO");
        return b;
    }

    private void save(ImportBatch b) {
        repository.save(b);
    }
}
