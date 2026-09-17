package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-G3b — provenance persistence + cancel-cascade contract on
 * {@link UspsLabelQueueServiceImpl}. Sits alongside the pre-existing
 * {@code UspsLabelQueueServiceTest} which covers the pre-G3b enqueue
 * shape; this test pins the two new capabilities:
 * <ol>
 *   <li>{@code EnqueueRequest} with {@code sourceType} + {@code importBatchId}
 *       reaches the persisted row verbatim (nullable when omitted via the
 *       3-arg legacy constructor).</li>
 *   <li>{@code cancelPending(batchId)} flips only QUEUED rows for that
 *       batch to CANCELLED, skips a row that raced to PROCESSING between
 *       the fetch and the flip, returns 0 on a non-positive batch id.</li>
 * </ol>
 */
class UspsLabelQueueServiceSourceTypeTest {

    private UspsLabelQueueRepository repo;
    private UspsLabelQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(UspsLabelQueueRepository.class);
        service = new UspsLabelQueueServiceImpl(repo, 55L);
    }

    // ================================================================
    // enqueue — provenance persisted
    // ================================================================

    @Test
    void enqueue_withSourceAndBatch_persistsBothOnRow() {
        when(repo.findByShipmentId(2001L)).thenReturn(Optional.empty());
        when(repo.countByStatus(Status.QUEUED)).thenReturn(0L);
        when(repo.countByStatusAndTenantCode(Status.QUEUED, "ACME")).thenReturn(0L);
        when(repo.save(any(UspsLabelQueueItem.class)))
                .thenAnswer(inv -> {
                    UspsLabelQueueItem row = inv.getArgument(0);
                    row.setId(555L);
                    return row;
                });

        EnqueueRequest req = new EnqueueRequest(
                "ACME", 2001L, 100,
                UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND, 999L);
        EnqueueResult result = service.enqueue(req);
        assertNotNull(result);

        ArgumentCaptor<UspsLabelQueueItem> cap = ArgumentCaptor.forClass(UspsLabelQueueItem.class);
        verify(repo).save(cap.capture());
        UspsLabelQueueItem persisted = cap.getValue();
        assertEquals(UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND, persisted.getSourceType());
        assertEquals(999L, persisted.getImportBatchId());
    }

    @Test
    void enqueue_legacyThreeArgCtor_persistsNullProvenance() {
        when(repo.findByShipmentId(2002L)).thenReturn(Optional.empty());
        when(repo.countByStatus(Status.QUEUED)).thenReturn(0L);
        when(repo.countByStatusAndTenantCode(Status.QUEUED, "ACME")).thenReturn(0L);
        when(repo.save(any(UspsLabelQueueItem.class)))
                .thenAnswer(inv -> {
                    UspsLabelQueueItem row = inv.getArgument(0);
                    row.setId(556L);
                    return row;
                });

        service.enqueue(new EnqueueRequest("ACME", 2002L, 0));

        ArgumentCaptor<UspsLabelQueueItem> cap = ArgumentCaptor.forClass(UspsLabelQueueItem.class);
        verify(repo).save(cap.capture());
        assertNull(cap.getValue().getSourceType(),
                "3-arg legacy ctor must land rows with source_type NULL for the dashboard's 'unknown' bucket");
        assertNull(cap.getValue().getImportBatchId());
    }

    // ================================================================
    // cancelPending
    // ================================================================

    @Test
    void cancelPending_flipsQueuedRowsForBatchToCancelled() {
        UspsLabelQueueItem a = row(1L, Status.QUEUED, 42L);
        UspsLabelQueueItem b = row(2L, Status.QUEUED, 42L);
        when(repo.findByImportBatchIdAndStatus(42L, Status.QUEUED))
                .thenReturn(List.of(a, b));

        int cancelled = service.cancelPending(42L);

        assertEquals(2, cancelled);
        assertEquals(Status.CANCELLED, a.getStatus());
        assertEquals(Status.CANCELLED, b.getStatus());
        assertNotNull(a.getCompletedAt());
        assertNotNull(b.getCompletedAt());
        verify(repo, times(2)).save(any(UspsLabelQueueItem.class));
    }

    @Test
    void cancelPending_skipsRowThatRacedToProcessing() {
        UspsLabelQueueItem raced = row(3L, Status.PROCESSING, 43L);   // simulate late-flip
        UspsLabelQueueItem live = row(4L, Status.QUEUED, 43L);
        when(repo.findByImportBatchIdAndStatus(43L, Status.QUEUED))
                .thenReturn(List.of(raced, live));

        int cancelled = service.cancelPending(43L);

        assertEquals(1, cancelled);
        assertEquals(Status.PROCESSING, raced.getStatus(),
                "PROCESSING row must be left alone — processor owns terminal resolution");
        assertEquals(Status.CANCELLED, live.getStatus());
        verify(repo, times(1)).save(any(UspsLabelQueueItem.class));
    }

    @Test
    void cancelPending_nonPositiveBatchId_returnsZeroAndSkipsQuery() {
        assertEquals(0, service.cancelPending(0L));
        assertEquals(0, service.cancelPending(-1L));
        verify(repo, never()).findByImportBatchIdAndStatus(any(), any());
    }

    @Test
    void cancelPending_noMatchingRows_returnsZeroWithoutSaves() {
        when(repo.findByImportBatchIdAndStatus(44L, Status.QUEUED))
                .thenReturn(List.of());
        assertEquals(0, service.cancelPending(44L));
        verify(repo, never()).save(any());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem row(long id, Status status, long importBatchId) {
        return UspsLabelQueueItem.builder()
                .id(id)
                .tenantCode("ACME")
                .shipmentId(id * 10)
                .priority(100)
                .status(status)
                .retryCount(0)
                .sourceType(UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND)
                .importBatchId(importBatchId)
                .build();
    }
}
