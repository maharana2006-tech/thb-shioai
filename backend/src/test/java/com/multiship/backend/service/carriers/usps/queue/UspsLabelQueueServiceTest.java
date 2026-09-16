package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for {@link UspsLabelQueueServiceImpl}. No Spring
 * context, no @SpringBootTest - the JPA repository is mocked, so these
 * tests run in milliseconds and pin the service's behaviour (not JPA's).
 *
 * <p>Design decision documented in
 * {@link UspsLabelQueueServiceImpl#enqueue(EnqueueRequest)}: a second
 * enqueue for the same {@code shipmentId} throws
 * {@link IllegalStateException}. We test both branches (already-present
 * row AND the unique-constraint race).
 */
class UspsLabelQueueServiceTest {

    private UspsLabelQueueRepository repo;
    private UspsLabelQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(UspsLabelQueueRepository.class);
        service = new UspsLabelQueueServiceImpl(repo, 55L);
    }

    // ================================================================
    // enqueue
    // ================================================================

    @Test
    void enqueue_persistsNewRow_andReturnsIdWithEstimatedStart() {
        when(repo.findByShipmentId(1001L)).thenReturn(Optional.empty());
        // No queued rows yet, so estimated start is "now".
        when(repo.countByStatus(Status.QUEUED)).thenReturn(0L);
        when(repo.countByStatusAndTenantCode(Status.QUEUED, "ACME")).thenReturn(0L);
        UspsLabelQueueItem saved = UspsLabelQueueItem.builder()
                .id(42L)
                .tenantCode("ACME").shipmentId(1001L)
                .priority(100).status(Status.QUEUED).retryCount(0)
                .build();
        when(repo.save(any(UspsLabelQueueItem.class))).thenReturn(saved);

        EnqueueResult result = service.enqueue(new EnqueueRequest("ACME", 1001L, 0));

        assertEquals(42L, result.queueItemId());
        assertNotNull(result.estimatedStartAt());

        ArgumentCaptor<UspsLabelQueueItem> cap = ArgumentCaptor.forClass(UspsLabelQueueItem.class);
        verify(repo).save(cap.capture());
        UspsLabelQueueItem persisted = cap.getValue();
        assertEquals("ACME", persisted.getTenantCode());
        assertEquals(1001L, persisted.getShipmentId());
        assertEquals(Status.QUEUED, persisted.getStatus());
        // Priority 0 requested -> default 100 applied.
        assertEquals(UspsLabelQueueServiceImpl.DEFAULT_PRIORITY, persisted.getPriority());
        assertEquals(0, persisted.getRetryCount());
    }

    @Test
    void enqueue_respectsExplicitPriority() {
        when(repo.findByShipmentId(1002L)).thenReturn(Optional.empty());
        when(repo.countByStatus(Status.QUEUED)).thenReturn(0L);
        when(repo.save(any(UspsLabelQueueItem.class)))
                .thenAnswer(inv -> {
                    UspsLabelQueueItem it = inv.getArgument(0);
                    it.setId(43L);
                    return it;
                });

        service.enqueue(new EnqueueRequest("ACME", 1002L, 5));

        ArgumentCaptor<UspsLabelQueueItem> cap = ArgumentCaptor.forClass(UspsLabelQueueItem.class);
        verify(repo).save(cap.capture());
        assertEquals(5, cap.getValue().getPriority());
    }

    @Test
    void enqueue_secondCallForSameShipment_throwsIllegalState() {
        UspsLabelQueueItem existing = UspsLabelQueueItem.builder()
                .id(9L).tenantCode("ACME").shipmentId(1001L)
                .priority(100).status(Status.QUEUED).retryCount(0)
                .enqueuedAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(repo.findByShipmentId(1001L)).thenReturn(Optional.of(existing));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.enqueue(new EnqueueRequest("ACME", 1001L, 100)));
        assertTrue(thrown.getMessage().contains("1001"),
                "Error message should identify the shipment id");

        // Design decision: no save() when the row already exists.
        verify(repo, never()).save(any());
    }

    @Test
    void enqueue_uniqueConstraintRace_wrappedAsIllegalState() {
        // findByShipmentId sees no row (first check), then save() races
        // with a concurrent writer and the DB rejects the second insert
        // with DataIntegrityViolationException. Service converts to
        // IllegalStateException so callers have one exception type to
        // catch for "already queued".
        when(repo.findByShipmentId(1001L)).thenReturn(Optional.empty());
        when(repo.save(any(UspsLabelQueueItem.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint uk_usps_label_queue_shipment"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.enqueue(new EnqueueRequest("ACME", 1001L, 100)));
        assertTrue(thrown.getMessage().contains("concurrently"),
                "Race message should mention concurrency");
    }

    @Test
    void enqueue_rejectsBlankTenant() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue(new EnqueueRequest("", 1L, 100)));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue(new EnqueueRequest(null, 1L, 100)));
    }

    @Test
    void enqueue_rejectsNullShipmentId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue(new EnqueueRequest("ACME", null, 100)));
    }

    // ================================================================
    // getMetrics
    // ================================================================

    @Test
    void getMetrics_returnsCorrectDepthAndBreakdown() {
        when(repo.countByStatus(Status.QUEUED)).thenReturn(150L);
        when(repo.countByStatus(Status.PROCESSING)).thenReturn(2L);
        when(repo.countByStatus(Status.DONE)).thenReturn(9000L);
        when(repo.countByStatus(Status.FAILED)).thenReturn(3L);
        when(repo.countByStatus(Status.CANCELLED)).thenReturn(11L);
        when(repo.findByStatusAndCompletedAtAfter(eq(Status.DONE), any(LocalDateTime.class)))
                .thenReturn(sampleRecentDone());
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(List.of(
                        item(1L, "ACME", 100, LocalDateTime.now().minusMinutes(5)),
                        item(2L, "OTHER", 100, LocalDateTime.now().minusMinutes(3))));
        when(repo.findQueueDepthByTenant(Status.QUEUED))
                .thenReturn(List.of(
                        tenantDepth("ACME", 100L),
                        tenantDepth("OTHER", 50L)));

        UspsLabelQueueMetricsDTO m = service.getMetrics();

        assertNull(m.getTenantCode(), "Platform-wide response should have null tenantCode");
        assertEquals(150L, m.getQueuedDepth());
        assertEquals(2L, m.getProcessingCount());
        assertEquals(9000L, m.getDoneCount());
        assertEquals(3L, m.getFailedCount());
        assertEquals(11L, m.getCancelledCount());
        assertEquals(55L, m.getConfiguredHourlyCap());
        assertNotNull(m.getPerTenantDepth());
        assertEquals(2, m.getPerTenantDepth().size());
        assertEquals("ACME", m.getPerTenantDepth().get(0).getTenantCode());
        assertEquals(100L, m.getPerTenantDepth().get(0).getQueuedDepth());

        // Recent-done sample -> current hourly pace + non-zero avg.
        assertEquals(3L, m.getCurrentHourlyPace());
        assertTrue(m.getAverageProcessingTimeMs() > 0);

        // Estimated wait > 0 given 150 queued at 55/hr -> ~163 min.
        assertTrue(m.getEstimatedWaitSeconds() > 0);
        assertNotNull(m.getEstimatedStartAt());
        // Oldest queued age should be ~5 minutes (300s), give or take
        // clock jitter.
        assertTrue(m.getOldestQueuedAgeSeconds() >= 290,
                "Oldest age should be about 5 minutes");
    }

    @Test
    void getMetricsForTenant_scopesDepthAndOmitsBreakdown() {
        when(repo.countByStatusAndTenantCode(Status.QUEUED, "ACME")).thenReturn(50L);
        when(repo.countByStatusAndTenantCode(Status.PROCESSING, "ACME")).thenReturn(1L);
        when(repo.countByStatusAndTenantCode(Status.DONE, "ACME")).thenReturn(500L);
        when(repo.countByStatusAndTenantCode(Status.FAILED, "ACME")).thenReturn(0L);
        when(repo.countByStatusAndTenantCode(Status.CANCELLED, "ACME")).thenReturn(0L);
        when(repo.findByStatusAndCompletedAtAfter(eq(Status.DONE), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(List.of());
        // Two active tenants -> fair-share halves the per-tenant pace.
        when(repo.findQueueDepthByTenant(Status.QUEUED))
                .thenReturn(List.of(
                        tenantDepth("ACME", 50L),
                        tenantDepth("OTHER", 20L)));

        UspsLabelQueueMetricsDTO m = service.getMetricsForTenant("ACME");

        assertEquals("ACME", m.getTenantCode());
        assertEquals(50L, m.getQueuedDepth());
        assertEquals(1L, m.getProcessingCount());
        assertEquals(500L, m.getDoneCount());
        assertNull(m.getPerTenantDepth(), "Tenant-scoped response omits perTenantDepth");
        // Fair-share: 55/hr / 2 tenants = 27.5/hr per tenant.
        // 50 queued / 27.5 per hour = ~1.82 hr = 6545s (ceiling).
        assertTrue(m.getEstimatedWaitSeconds() > 6000,
                "Fair-share slice should stretch the wait beyond the platform-wide estimate");
    }

    @Test
    void getMetricsForTenant_rejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getMetricsForTenant(""));
        assertThrows(IllegalArgumentException.class,
                () -> service.getMetricsForTenant(null));
    }

    @Test
    void getMetrics_zeroQueue_returnsZeroWaitAndOldestAge() {
        when(repo.countByStatus(any())).thenReturn(0L);
        when(repo.findByStatusAndCompletedAtAfter(eq(Status.DONE), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED))
                .thenReturn(List.of());
        when(repo.findQueueDepthByTenant(Status.QUEUED))
                .thenReturn(List.of());

        UspsLabelQueueMetricsDTO m = service.getMetrics();

        assertEquals(0L, m.getQueuedDepth());
        assertEquals(0L, m.getEstimatedWaitSeconds());
        assertEquals(0L, m.getOldestQueuedAgeSeconds());
        assertEquals(0L, m.getAverageProcessingTimeMs());
        assertEquals(0L, m.getCurrentHourlyPace());
    }

    // ================================================================
    // cancel
    // ================================================================

    @Test
    void cancel_onQueuedRow_flipsStatusAndReturnsTrue() {
        UspsLabelQueueItem row = UspsLabelQueueItem.builder()
                .id(7L).tenantCode("ACME").shipmentId(1001L)
                .priority(100).status(Status.QUEUED).retryCount(0)
                .build();
        when(repo.findById(7L)).thenReturn(Optional.of(row));
        when(repo.save(any(UspsLabelQueueItem.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean cancelled = service.cancel(7L);

        assertTrue(cancelled);
        assertEquals(Status.CANCELLED, row.getStatus());
        assertNotNull(row.getCompletedAt(),
                "cancel() must stamp completed_at for lifecycle observability");
        verify(repo, times(1)).save(row);
    }

    @Test
    void cancel_onProcessingRow_returnsFalseAndLeavesRowUntouched() {
        UspsLabelQueueItem row = UspsLabelQueueItem.builder()
                .id(7L).tenantCode("ACME").shipmentId(1001L)
                .priority(100).status(Status.PROCESSING).retryCount(0)
                .startedAt(LocalDateTime.now().minusSeconds(5))
                .build();
        when(repo.findById(7L)).thenReturn(Optional.of(row));

        boolean cancelled = service.cancel(7L);

        assertFalse(cancelled);
        assertEquals(Status.PROCESSING, row.getStatus(), "Status must be unchanged");
        assertNull(row.getCompletedAt(), "completed_at must not be stamped");
        verify(repo, never()).save(any());
    }

    @Test
    void cancel_onTerminalRow_returnsFalse() {
        for (Status s : List.of(Status.DONE, Status.FAILED, Status.CANCELLED)) {
            UspsLabelQueueItem row = UspsLabelQueueItem.builder()
                    .id(7L).tenantCode("ACME").shipmentId(1001L)
                    .priority(100).status(s).retryCount(0).build();
            when(repo.findById(7L)).thenReturn(Optional.of(row));

            assertFalse(service.cancel(7L),
                    "cancel() on " + s + " must return false");
        }
    }

    @Test
    void cancel_missingRow_returnsFalse() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertFalse(service.cancel(99L));
        verify(repo, never()).save(any());
    }

    @Test
    void cancel_nullId_returnsFalseWithoutRepositoryHit() {
        assertFalse(service.cancel(null));
        verify(repo, never()).findById(any());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem item(long id, String tenant, int priority, LocalDateTime enqueuedAt) {
        return UspsLabelQueueItem.builder()
                .id(id).tenantCode(tenant).shipmentId(1000L + id)
                .priority(priority).status(Status.QUEUED).retryCount(0)
                .enqueuedAt(enqueuedAt)
                .build();
    }

    private static List<UspsLabelQueueItem> sampleRecentDone() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(30);
        return List.of(
                doneRow(1L, base, base.plusSeconds(3)),
                doneRow(2L, base, base.plusSeconds(4)),
                doneRow(3L, base, base.plusSeconds(5)));
    }

    private static UspsLabelQueueItem doneRow(long id, LocalDateTime started, LocalDateTime finished) {
        return UspsLabelQueueItem.builder()
                .id(id).tenantCode("ACME").shipmentId(2000L + id)
                .priority(100).status(Status.DONE).retryCount(0)
                .startedAt(started).completedAt(finished)
                .build();
    }

    private static com.multiship.backend.repository.UspsLabelQueueRepository.TenantDepth
    tenantDepth(String code, long depth) {
        return new com.multiship.backend.repository.UspsLabelQueueRepository.TenantDepth() {
            @Override public String getTenantCode() { return code; }
            @Override public Long getDepth() { return depth; }
        };
    }

    /** Local Mockito ArgumentMatchers.eq alias to avoid import bloat. */
    private static <T> T eq(T val) { return org.mockito.ArgumentMatchers.eq(val); }
}
