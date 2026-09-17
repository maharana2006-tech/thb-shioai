package com.multiship.backend.service.carriers.usps.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-G3b — bridges USPS queue-terminal events back to the originating
 * import row's {@code generatedStatus}. Closes audit finding U5.
 *
 * <p>Coverage matrix pinned here:
 * <ul>
 *   <li>Null event, event without an importBatchId (manual / bulk), and
 *       non-terminal statuses → no repo touch.</li>
 *   <li>DONE with matching QUEUED_USPS row → flips to GENERATED + stamps
 *       the tracking number.</li>
 *   <li>FAILED with matching QUEUED_USPS row → flips to FAILED + stamps
 *       a queue-attributed message.</li>
 *   <li>Row already GENERATED / FAILED → left alone (idempotent).</li>
 *   <li>orderNo mismatch → left alone.</li>
 *   <li>Malformed rowsJson or missing batch → swallowed (fail-open).</li>
 * </ul>
 */
class UspsQueueImportRowReconcilerTest {

    private ImportBatchRepository importBatchRepository;
    private ObjectMapper objectMapper;
    private UspsQueueImportRowReconciler reconciler;

    @BeforeEach
    void setUp() {
        importBatchRepository = mock(ImportBatchRepository.class);
        objectMapper = new ObjectMapper();
        reconciler = new UspsQueueImportRowReconciler(importBatchRepository, objectMapper);
    }

    // ================================================================
    // early-return guards
    // ================================================================

    @Test
    void onTerminal_nullEvent_noRepoTouch() {
        reconciler.onTerminal(null);
        verifyNoInteractions(importBatchRepository);
    }

    @Test
    void onTerminal_nullImportBatchId_shortCircuits() {
        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                1L, null, 100L, UspsLabelQueueItem.Status.DONE, "94001234567890", null);
        reconciler.onTerminal(evt);
        verifyNoInteractions(importBatchRepository);
    }

    @Test
    void onTerminal_nonTerminalStatus_ignored() {
        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                1L, 42L, 100L, UspsLabelQueueItem.Status.PROCESSING, null, null);
        reconciler.onTerminal(evt);
        verifyNoInteractions(importBatchRepository);
    }

    @Test
    void onTerminal_batchNotFound_swallowed() {
        when(importBatchRepository.findById(42L)).thenReturn(Optional.empty());
        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                1L, 42L, 100L, UspsLabelQueueItem.Status.DONE, "94001234567890", null);
        reconciler.onTerminal(evt);
        verify(importBatchRepository).findById(42L);
        verify(importBatchRepository, never()).save(any());
    }

    // ================================================================
    // happy path — DONE flips QUEUED_USPS -> GENERATED
    // ================================================================

    @Test
    void onTerminal_done_flipsQueuedRowToGeneratedAndStampsTracking() throws Exception {
        long batchId = 42L;
        int orderNo = 100;
        String tracking = "9400123456789012345678";

        ImportBatch batch = buildBatch(batchId, List.of(
                queuedRow(1, orderNo),
                queuedRow(2, 999)));       // mismatched — must stay put
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                55L, batchId, (long) orderNo, UspsLabelQueueItem.Status.DONE, tracking, null);
        reconciler.onTerminal(evt);

        ArgumentCaptor<ImportBatch> saved = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(saved.capture());
        List<OrderImportRowDTO> rowsAfter = objectMapper.readValue(
                saved.getValue().getRowsJson(), new TypeReference<>() {});
        assertEquals(2, rowsAfter.size());

        OrderImportRowDTO flipped = rowsAfter.stream()
                .filter(r -> r.getGeneratedOrderNo() != null && r.getGeneratedOrderNo() == orderNo)
                .findFirst().orElseThrow();
        assertEquals("GENERATED", flipped.getGeneratedStatus());
        assertEquals(tracking, flipped.getGeneratedTrackingNumber());
        assertNotNull(flipped.getGeneratedMessage());
        assertTrue(flipped.getGeneratedMessage().toLowerCase().contains("queue"));

        OrderImportRowDTO other = rowsAfter.stream()
                .filter(r -> r.getGeneratedOrderNo() != null && r.getGeneratedOrderNo() == 999)
                .findFirst().orElseThrow();
        assertEquals("QUEUED_USPS", other.getGeneratedStatus(),
                "mismatched-order row must stay QUEUED_USPS");
    }

    // ================================================================
    // happy path — FAILED flips + stamps queue-attributed error
    // ================================================================

    @Test
    void onTerminal_failed_flipsQueuedRowToFailedAndStampsMessage() throws Exception {
        long batchId = 43L;
        int orderNo = 200;

        ImportBatch batch = buildBatch(batchId, List.of(queuedRow(1, orderNo)));
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                77L, batchId, (long) orderNo, UspsLabelQueueItem.Status.FAILED,
                null, "USPS returned 429 five times in a row");
        reconciler.onTerminal(evt);

        ArgumentCaptor<ImportBatch> saved = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(saved.capture());
        OrderImportRowDTO row = objectMapper.readValue(
                saved.getValue().getRowsJson(),
                new TypeReference<List<OrderImportRowDTO>>() {}).get(0);
        assertEquals("FAILED", row.getGeneratedStatus());
        assertTrue(row.getGeneratedMessage().contains("USPS returned 429"),
                "queue error must surface in the row message");
        assertTrue(row.getGeneratedMessage().toLowerCase().contains("queue"),
                "message must be queue-attributed so ops know the source");
    }

    // ================================================================
    // idempotency — non-QUEUED_USPS rows are left alone
    // ================================================================

    @Test
    void onTerminal_alreadyGeneratedRow_noFlip_noSave() {
        long batchId = 44L;
        int orderNo = 300;

        OrderImportRowDTO row = queuedRow(1, orderNo);
        row.setGeneratedStatus("GENERATED");       // simulate a prior resolve
        ImportBatch batch = buildBatch(batchId, List.of(row));
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                88L, batchId, (long) orderNo, UspsLabelQueueItem.Status.DONE, "9400", null);
        reconciler.onTerminal(evt);

        verify(importBatchRepository).findById(batchId);
        verify(importBatchRepository, never()).save(any());
    }

    // ================================================================
    // orderNo mismatch — no flip, no save
    // ================================================================

    @Test
    void onTerminal_orderNoMismatch_noFlip_noSave() {
        long batchId = 45L;
        ImportBatch batch = buildBatch(batchId, List.of(queuedRow(1, 500)));
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                99L, batchId, 999L, UspsLabelQueueItem.Status.DONE, "9400", null);
        reconciler.onTerminal(evt);

        verify(importBatchRepository).findById(batchId);
        verify(importBatchRepository, never()).save(any());
    }

    // ================================================================
    // malformed rowsJson — swallowed
    // ================================================================

    @Test
    void onTerminal_malformedRowsJson_swallowed() {
        long batchId = 46L;
        ImportBatch batch = new ImportBatch();
        batch.setId(batchId);
        batch.setRowsJson("not-json{{{");
        when(importBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        UspsLabelQueueTerminalEvent evt = new UspsLabelQueueTerminalEvent(
                111L, batchId, 100L, UspsLabelQueueItem.Status.DONE, "9400", null);
        reconciler.onTerminal(evt);

        verify(importBatchRepository).findById(batchId);
        verify(importBatchRepository, never()).save(any());
    }

    // ================================================================
    // helpers
    // ================================================================

    private ImportBatch buildBatch(long id, List<OrderImportRowDTO> rows) {
        ImportBatch b = new ImportBatch();
        b.setId(id);
        try {
            b.setRowsJson(objectMapper.writeValueAsString(rows));
        } catch (Exception e) {
            throw new AssertionError("fixture serialisation failed", e);
        }
        return b;
    }

    private static OrderImportRowDTO queuedRow(int rowNumber, int generatedOrderNo) {
        OrderImportRowDTO r = OrderImportRowDTO.builder()
                .rowNumber(rowNumber)
                .clientCode("ACME")
                .recipientName("Jane " + rowNumber)
                .build();
        r.setGeneratedOrderNo(generatedOrderNo);
        r.setGeneratedStatus("QUEUED_USPS");
        return r;
    }
}
