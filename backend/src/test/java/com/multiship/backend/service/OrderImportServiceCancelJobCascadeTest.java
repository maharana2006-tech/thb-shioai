package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.ImportGenerationJobRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-G3b (M-B4) — cancelling an import batch must also cancel any live
 * USPS_DIRECT queue rows it enqueued, otherwise the 55/hr queue keeps
 * draining pieces long after the operator terminated the batch.
 *
 * <p>Contract pinned here:
 * <ol>
 *   <li>Cancel of a live IN_PROGRESS batch calls
 *       {@link UspsLabelQueueService#cancelPending(long)} with the
 *       batch id.</li>
 *   <li>The cascade is best-effort — a runtime exception from
 *       {@code cancelPending} does NOT fail the operator-facing cancel
 *       response (still returns success).</li>
 *   <li>Cancel of an already-terminal batch never touches the queue at
 *       all (guard placed before the cascade call site).</li>
 *   <li>When the queue-service bean isn't wired (2-arg legacy test
 *       constructor / pure-Mockito path), the cascade block silently
 *       no-ops.</li>
 * </ol>
 */
class OrderImportServiceCancelJobCascadeTest {

    private CarrierService carrierService;
    private ImportBatchRepository importBatchRepository;
    private ImportGenerationJobRepository generationJobRepository;
    private UspsLabelQueueService uspsLabelQueueService;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        importBatchRepository = mock(ImportBatchRepository.class);
        generationJobRepository = mock(ImportGenerationJobRepository.class);
        uspsLabelQueueService = mock(UspsLabelQueueService.class);
        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
        ReflectionTestUtils.setField(service, "generationJobRepository", generationJobRepository);
        ReflectionTestUtils.setField(service, "uspsLabelQueueService", uspsLabelQueueService);
    }

    // ================================================================
    // fixtures
    // ================================================================

    private static ImportBatch generatingBatch(long id) {
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus("IN_PROGRESS");   // isGenerating() -> true
        b.setRowsJson(null);          // requireMatch no-ops when tenantScope null
        return b;
    }

    private static ImportBatch terminalBatch(long id, String terminal) {
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus(terminal);
        return b;
    }

    // ================================================================
    // happy path — cascade fires
    // ================================================================

    @Test
    void cancelGeneration_generatingBatch_cascadesToQueueCancelPending() {
        long batchId = 4242L;
        when(importBatchRepository.findById(batchId))
                .thenReturn(Optional.of(generatingBatch(batchId)));
        when(uspsLabelQueueService.cancelPending(batchId)).thenReturn(7);

        ApiResponse<String> resp = service.cancelGeneration(batchId);

        assertNotNull(resp);
        assertEquals("success", resp.getStatus());
        verify(generationJobRepository).requestCancel(batchId);
        verify(uspsLabelQueueService).cancelPending(eq(batchId));
    }

    // ================================================================
    // best-effort — cascade throws, operator response still succeeds
    // ================================================================

    @Test
    void cancelGeneration_cascadeThrows_stillReturnsSuccess() {
        long batchId = 4243L;
        when(importBatchRepository.findById(batchId))
                .thenReturn(Optional.of(generatingBatch(batchId)));
        when(uspsLabelQueueService.cancelPending(batchId))
                .thenThrow(new RuntimeException("boom"));

        ApiResponse<String> resp = service.cancelGeneration(batchId);

        assertNotNull(resp);
        assertEquals("success", resp.getStatus(),
                "Cascade failure must not fail the operator's cancel — log-and-continue is the contract");
        verify(uspsLabelQueueService).cancelPending(batchId);
    }

    // ================================================================
    // guard: terminal batch — cascade never fires
    // ================================================================

    @Test
    void cancelGeneration_alreadyCompleted_neverCallsQueueCascade() {
        long batchId = 4244L;
        when(importBatchRepository.findById(batchId))
                .thenReturn(Optional.of(terminalBatch(batchId, "COMPLETE")));

        ApiResponse<String> resp = service.cancelGeneration(batchId);

        assertNotNull(resp);
        assertEquals("error", resp.getStatus());
        verify(uspsLabelQueueService, never()).cancelPending(batchId);
    }

    @Test
    void cancelGeneration_alreadyCancelled_neverCallsQueueCascade() {
        long batchId = 4245L;
        when(importBatchRepository.findById(batchId))
                .thenReturn(Optional.of(terminalBatch(batchId, "CANCELLED")));

        ApiResponse<String> resp = service.cancelGeneration(batchId);

        assertNotNull(resp);
        assertEquals("error", resp.getStatus());
        verifyNoInteractions(uspsLabelQueueService);
    }

    // ================================================================
    // bean absent — cascade block silently no-ops
    // ================================================================

    @Test
    void cancelGeneration_queueServiceBeanAbsent_noNpeNoCall() {
        long batchId = 4246L;
        // Rewire without the queue service so the cascade block sees null.
        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
        ReflectionTestUtils.setField(service, "generationJobRepository", generationJobRepository);
        when(importBatchRepository.findById(batchId))
                .thenReturn(Optional.of(generatingBatch(batchId)));

        ApiResponse<String> resp = service.cancelGeneration(batchId);

        assertNotNull(resp);
        assertEquals("success", resp.getStatus());
        verifyNoInteractions(uspsLabelQueueService);
    }
}
