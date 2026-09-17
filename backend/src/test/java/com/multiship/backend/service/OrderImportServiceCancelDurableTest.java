package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.ImportGenerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-S2 (audit S-B3) — pins the durable cancel signal introduced with V64
 * {@code import_batch.cancel_requested_at}:
 * <ol>
 *   <li>{@code cancelGeneration} stamps the column on the batch row.</li>
 *   <li>{@code isBatchCancelRequestedDurable} reads it back and returns
 *       true iff non-null.</li>
 *   <li>DB read failure fails open (returns false) so a broken lookup
 *       can't ignore a fresh in-memory cancel that already fired via
 *       {@code cancelledBatchIds}.</li>
 * </ol>
 */
class OrderImportServiceCancelDurableTest {

    private CarrierService carrierService;
    private ImportBatchRepository importBatchRepository;
    private ImportGenerationJobRepository generationJobRepository;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        importBatchRepository = mock(ImportBatchRepository.class);
        generationJobRepository = mock(ImportGenerationJobRepository.class);
        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
        ReflectionTestUtils.setField(service, "generationJobRepository", generationJobRepository);
    }

    private static ImportBatch generatingBatch(long id) {
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus("IN_PROGRESS");
        return b;
    }

    // ================================================================
    // cancelGeneration stamps the durable column
    // ================================================================

    @Test
    void cancelGeneration_stampsCancelRequestedAtOnBatchRow() {
        long batchId = 5000L;
        ImportBatch batch = generatingBatch(batchId);
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        ApiResponse<String> resp = service.cancelGeneration(batchId);

        assertEquals("success", resp.getStatus());
        ArgumentCaptor<ImportBatch> saved = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(saved.capture());
        assertNotNull(saved.getValue().getCancelRequestedAt(),
                "cancelGeneration must stamp the durable cancel signal so cross-JVM workers see it");
    }

    // ================================================================
    // isBatchCancelRequestedDurable reads the column
    // ================================================================

    @Test
    void durableCheck_returnsTrueWhenColumnIsSet() throws Exception {
        long batchId = 5001L;
        ImportBatch batch = generatingBatch(batchId);
        batch.setCancelRequestedAt(LocalDateTime.now());
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        assertTrue(invokeDurableCheck(batchId));
    }

    @Test
    void durableCheck_returnsFalseWhenColumnIsNull() throws Exception {
        long batchId = 5002L;
        ImportBatch batch = generatingBatch(batchId);
        batch.setCancelRequestedAt(null);
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        assertFalse(invokeDurableCheck(batchId));
    }

    @Test
    void durableCheck_returnsFalseWhenBatchNotFound() throws Exception {
        long batchId = 5003L;
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.empty());

        assertFalse(invokeDurableCheck(batchId),
                "Missing batch must not falsely fire cancel — the outer loop exits on its own next tick");
    }

    @Test
    void durableCheck_returnsFalseOnRepoException() throws Exception {
        long batchId = 5004L;
        when(importBatchRepository.findById(batchId))
                .thenThrow(new RuntimeException("DB unavailable"));

        assertFalse(invokeDurableCheck(batchId),
                "Fail-open: a broken DB lookup must not ignore in-memory cancel that already fired");
    }

    @Test
    void durableCheck_returnsFalseWhenRepoIsUnwired() throws Exception {
        // Legacy unit-test constructor path — no importBatchRepository wired.
        OrderImportServiceImpl bare = new OrderImportServiceImpl(carrierService);
        Method m = OrderImportServiceImpl.class
                .getDeclaredMethod("isBatchCancelRequestedDurable", long.class);
        m.setAccessible(true);
        boolean result = (Boolean) m.invoke(bare, 5005L);
        assertFalse(result);
    }

    private boolean invokeDurableCheck(long batchId) throws Exception {
        Method m = OrderImportServiceImpl.class
                .getDeclaredMethod("isBatchCancelRequestedDurable", long.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(service, batchId);
    }
}
