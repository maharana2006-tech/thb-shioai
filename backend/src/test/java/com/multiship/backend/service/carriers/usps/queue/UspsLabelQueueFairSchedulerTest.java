package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for {@link UspsLabelQueueFairScheduler}. The
 * repository is mocked to feed a canned queue-depth snapshot; the
 * scheduler's algorithm is deterministic so assertions can pin exact
 * IDs / ordering.
 */
class UspsLabelQueueFairSchedulerTest {

    private UspsLabelQueueRepository repo;
    private UspsLabelQueueFairScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = mock(UspsLabelQueueRepository.class);
        scheduler = new UspsLabelQueueFairScheduler(repo);
        // Default urgent-cap = 1 per tick (matches application.properties
        // default). Tests that need a different cap set it explicitly.
        scheduler.setUrgentPerTickCapForTest(1);
    }

    // ================================================================
    // Single tenant - straight FIFO
    // ================================================================

    @Test
    void singleTenant_returnsBatchSizeInPriorityAndEnqueueOrder() {
        // 100 rows for ACME, id 1..100, all priority 100, enqueue in
        // ascending id order.
        List<UspsLabelQueueItem> queue = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusMinutes(30);
        for (long i = 1; i <= 100; i++) {
            queue.add(row(i, "ACME", 100, base.plusSeconds(i)));
        }
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(queue);

        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(10);

        assertEquals(10, picked.size(), "Batch size cap must be respected");
        // FIFO within tenant: ids 1..10 in order.
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1L, picked.get(i).getId());
        }
    }

    @Test
    void singleTenant_batchLargerThanQueue_returnsAll() {
        List<UspsLabelQueueItem> queue = List.of(
                row(1L, "ACME", 100, LocalDateTime.now().minusMinutes(2)),
                row(2L, "ACME", 100, LocalDateTime.now().minusMinutes(1)));
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(queue);

        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(10);

        assertEquals(2, picked.size());
    }

    // ================================================================
    // Multi-tenant round-robin
    // ================================================================

    @Test
    void threeTenantsWith100Each_roundRobinsEqualShare() {
        List<UspsLabelQueueItem> queue = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        long id = 1;
        for (String tenant : List.of("ACME", "BETA", "GAMMA")) {
            for (int i = 0; i < 100; i++) {
                queue.add(row(id++, tenant, 100, base.plusSeconds(id)));
            }
        }
        // Repo returns in (priority, enqueued_at) order regardless of
        // tenant - matches what findByStatusOrderByPriorityAscEnqueuedAtAsc
        // guarantees. Sort matches enqueue order.
        queue.sort(Comparator.comparing(UspsLabelQueueItem::getEnqueuedAt));
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(queue);

        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(9);

        assertEquals(9, picked.size());
        Map<String, Long> perTenant = picked.stream()
                .collect(Collectors.groupingBy(UspsLabelQueueItem::getTenantCode,
                        Collectors.counting()));
        // 9 slots across 3 tenants -> exactly 3 each.
        assertEquals(3L, perTenant.get("ACME"));
        assertEquals(3L, perTenant.get("BETA"));
        assertEquals(3L, perTenant.get("GAMMA"));
    }

    @Test
    void smallTenantDrainsFirst_bigTenantBackfills() {
        // ACME has 2 rows, BIG has 100. batchSize 6.
        // Round-robin: ACME, BIG, ACME, BIG, BIG, BIG - so ACME takes
        // 2 slots, BIG takes 4.
        List<UspsLabelQueueItem> queue = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        long id = 1;
        queue.add(row(id++, "ACME", 100, base.plusSeconds(1)));
        queue.add(row(id++, "ACME", 100, base.plusSeconds(2)));
        for (int i = 0; i < 100; i++) {
            queue.add(row(id++, "BIG", 100, base.plusSeconds(3 + i)));
        }
        queue.sort(Comparator.comparing(UspsLabelQueueItem::getEnqueuedAt));
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(queue);

        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(6);

        assertEquals(6, picked.size());
        Map<String, Long> perTenant = picked.stream()
                .collect(Collectors.groupingBy(UspsLabelQueueItem::getTenantCode,
                        Collectors.counting()));
        assertEquals(2L, perTenant.get("ACME"),
                "Small tenant should drain entirely");
        assertEquals(4L, perTenant.get("BIG"),
                "Big tenant backfills the remaining slots");
    }

    // ================================================================
    // Urgent priority path
    // ================================================================

    @Test
    void urgentRowJumpsQueue_withinPerTickCap() {
        // 1 urgent row (priority=1) buried in a big queue of normal
        // rows. urgentPerTickCap=1 (default). Expect the urgent row
        // to be picked FIRST regardless of enqueue order.
        List<UspsLabelQueueItem> queue = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusMinutes(30);
        // Normal rows: id 1..10, priority 100.
        for (long i = 1; i <= 10; i++) {
            queue.add(row(i, "ACME", 100, base.plusSeconds(i)));
        }
        // Urgent row: id 999, priority 1, enqueued LAST.
        UspsLabelQueueItem urgent = row(999L, "OPS", 1, base.plusSeconds(1000));
        queue.add(urgent);
        // Sort by (priority asc, enqueued asc) so the urgent row is on top.
        queue.sort(Comparator
                .comparing(UspsLabelQueueItem::getPriority)
                .thenComparing(UspsLabelQueueItem::getEnqueuedAt));
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(queue);

        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(5);

        assertEquals(5, picked.size());
        assertEquals(999L, picked.get(0).getId(),
                "Urgent row must be the first picked");
    }

    @Test
    void urgentTenantCappedPerTick_othersStillGetSlots() {
        // 5 urgent rows for OPS, urgent-cap=1, batch=4. OPS gets 1
        // urgent slot; the other 3 slots round-robin across the
        // non-urgent bucket (ACME + BETA).
        List<UspsLabelQueueItem> queue = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusMinutes(30);
        long id = 1;
        for (int i = 0; i < 5; i++) {
            queue.add(row(id++, "OPS", 1, base.plusSeconds(id))); // urgent
        }
        for (int i = 0; i < 20; i++) {
            queue.add(row(id++, "ACME", 100, base.plusSeconds(id)));
        }
        for (int i = 0; i < 20; i++) {
            queue.add(row(id++, "BETA", 100, base.plusSeconds(id)));
        }
        queue.sort(Comparator
                .comparing(UspsLabelQueueItem::getPriority)
                .thenComparing(UspsLabelQueueItem::getEnqueuedAt));
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(queue);

        scheduler.setUrgentPerTickCapForTest(1);
        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(4);

        assertEquals(4, picked.size());
        Map<String, Long> perTenant = picked.stream()
                .collect(Collectors.groupingBy(UspsLabelQueueItem::getTenantCode,
                        Collectors.counting()));
        assertEquals(1L, perTenant.get("OPS"), "Urgent cap = 1");
        // Remaining 3 slots split across ACME + BETA - one goes 2 : 1
        // depending on iteration order, both must be present.
        assertNotNull(perTenant.get("ACME"));
        assertNotNull(perTenant.get("BETA"));
        assertEquals(3L, perTenant.get("ACME") + perTenant.get("BETA"));
    }

    @Test
    void urgentBucketWithHigherCap_takesMoreSlots() {
        // Same data, cap raised to 3 -> OPS gets 3 slots this tick.
        List<UspsLabelQueueItem> queue = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusMinutes(30);
        long id = 1;
        for (int i = 0; i < 5; i++) {
            queue.add(row(id++, "OPS", 1, base.plusSeconds(id)));
        }
        for (int i = 0; i < 20; i++) {
            queue.add(row(id++, "ACME", 100, base.plusSeconds(id)));
        }
        queue.sort(Comparator
                .comparing(UspsLabelQueueItem::getPriority)
                .thenComparing(UspsLabelQueueItem::getEnqueuedAt));
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(queue);

        scheduler.setUrgentPerTickCapForTest(3);
        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(5);

        assertEquals(5, picked.size());
        long opsCount = picked.stream()
                .filter(r -> "OPS".equals(r.getTenantCode())).count();
        assertEquals(3L, opsCount, "OPS should take exactly urgent-cap slots");
    }

    // ================================================================
    // Edge cases
    // ================================================================

    @Test
    void emptyQueue_returnsEmptyList() {
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(List.of());
        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(10);
        assertTrue(picked.isEmpty());
    }

    @Test
    void batchSizeZero_returnsEmptyList() {
        // Never even calls the repo when the caller asks for 0 rows.
        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(0);
        assertTrue(picked.isEmpty());
    }

    @Test
    void batchSizeNegative_returnsEmptyList() {
        List<UspsLabelQueueItem> picked = scheduler.pickNextBatch(-1);
        assertTrue(picked.isEmpty());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem row(long id, String tenant, int priority, LocalDateTime enqueuedAt) {
        return UspsLabelQueueItem.builder()
                .id(id).tenantCode(tenant).shipmentId(3000L + id)
                .priority(priority).status(Status.QUEUED).retryCount(0)
                .enqueuedAt(enqueuedAt)
                .build();
    }
}
