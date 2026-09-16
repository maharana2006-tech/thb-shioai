package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsRequest.PieceRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-F2 - pure-Mockito tests for
 * {@link UspsLabelQueueServiceImpl#enqueueMps(EnqueueMpsRequest)}. Kept
 * in its own class so the PR-F1 tests in
 * {@code UspsLabelQueueServiceTest} stay unchanged.
 */
class UspsLabelQueueServiceEnqueueMpsTest {

    private UspsLabelQueueRepository repo;
    private UspsLabelQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(UspsLabelQueueRepository.class);
        service = new UspsLabelQueueServiceImpl(repo, 55L);
    }

    // ================================================================
    // Validation
    // ================================================================

    @Test
    void enqueueMps_rejectsNullRequest() {
        assertThrows(IllegalArgumentException.class, () -> service.enqueueMps(null));
    }

    @Test
    void enqueueMps_rejectsBlankTenant() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        "", 42L, List.of(new PieceRequest(-1L, 1)), 0)));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        null, 42L, List.of(new PieceRequest(-1L, 1)), 0)));
    }

    @Test
    void enqueueMps_rejectsNullParent() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        "ACME", null, List.of(new PieceRequest(-1L, 1)), 0)));
    }

    @Test
    void enqueueMps_rejectsEmptyPieces() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        "ACME", 42L, List.of(), 0)));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        "ACME", 42L, null, 0)));
    }

    @Test
    void enqueueMps_rejectsNullShipmentIdInPiece() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        "ACME", 42L, List.of(new PieceRequest(null, 1)), 0)));
    }

    @Test
    void enqueueMps_rejectsNonPositiveSequenceNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        "ACME", 42L, List.of(new PieceRequest(-1L, 0)), 0)));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest(
                        "ACME", 42L, List.of(new PieceRequest(-1L, -1)), 0)));
    }

    @Test
    void enqueueMps_rejectsDuplicateShipmentIdsInsideBatch() {
        List<PieceRequest> pieces = List.of(
                new PieceRequest(-1L, 1),
                new PieceRequest(-2L, 2),
                new PieceRequest(-1L, 3));   // dup of first

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest("ACME", 42L, pieces, 0)));
        assertTrue(ex.getMessage().contains("duplicate"),
                "IAE message must call out the duplicate shipmentId");
        // Never touched the repo - fail-fast before persist.
        verify(repo, never()).saveAll(anyList());
    }

    // ================================================================
    // Happy path
    // ================================================================

    @Test
    void enqueueMps_persistsNRowsWithSharedParentAndDistinctSeq() {
        List<PieceRequest> pieces = List.of(
                new PieceRequest(-1L, 1),
                new PieceRequest(-2L, 2),
                new PieceRequest(-3L, 3));
        when(repo.saveAll(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<UspsLabelQueueItem> rows = (List<UspsLabelQueueItem>) inv.getArgument(0);
            // Simulate JPA id generation.
            for (int i = 0; i < rows.size(); i++) rows.get(i).setId(100L + i);
            return rows;
        });
        // For the estimated-start calc.
        when(repo.countByStatusAndTenantCode(Status.QUEUED, "ACME")).thenReturn(0L);

        EnqueueMpsResult result = service.enqueueMps(
                new EnqueueMpsRequest("ACME", 42L, pieces, 0));

        assertNotNull(result);
        assertEquals(42L, result.parentOrderNo());
        assertEquals(3, result.enqueuedCount());
        assertNotNull(result.estimatedFirstStartAt());
        assertNotNull(result.estimatedLastCompleteAt());
        // Last completion must be at least equal to first start; on a
        // 3-piece batch the drain is trivial.
        assertTrue(!result.estimatedLastCompleteAt().isBefore(result.estimatedFirstStartAt()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UspsLabelQueueItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).saveAll(captor.capture());
        List<UspsLabelQueueItem> persisted = captor.getValue();
        assertEquals(3, persisted.size());
        for (int i = 0; i < 3; i++) {
            UspsLabelQueueItem row = persisted.get(i);
            assertEquals("ACME", row.getTenantCode());
            assertEquals(42L, row.getParentOrderNo());
            assertEquals(i + 1, row.getSequenceNumber());
            assertEquals(Status.QUEUED, row.getStatus());
            // priority=0 -> DEFAULT_PRIORITY.
            assertEquals(UspsLabelQueueServiceImpl.DEFAULT_PRIORITY, row.getPriority());
        }
    }

    @Test
    void enqueueMps_thousandPieces_persistsAllInOneSaveAll() {
        List<PieceRequest> pieces = new ArrayList<>(1000);
        for (int i = 1; i <= 1000; i++) {
            pieces.add(new PieceRequest((long) -i, i));
        }
        when(repo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.countByStatusAndTenantCode(Status.QUEUED, "ACME")).thenReturn(0L);

        EnqueueMpsResult result = service.enqueueMps(
                new EnqueueMpsRequest("ACME", 999L, pieces, 0));

        assertEquals(1000, result.enqueuedCount());
        verify(repo, times(1)).saveAll(anyList());
    }

    // ================================================================
    // Cross-batch conflict
    // ================================================================

    @Test
    void enqueueMps_dbUniqueViolation_wrappedAsIllegalState() {
        List<PieceRequest> pieces = List.of(
                new PieceRequest(-1L, 1),
                new PieceRequest(-2L, 2));
        when(repo.saveAll(anyList())).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint uk_usps_label_queue_shipment"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.enqueueMps(new EnqueueMpsRequest("ACME", 42L, pieces, 0)));
        assertTrue(ex.getMessage().contains("42"),
                "IllegalState wrap must name the parentOrderNo for operator triage");
    }
}
