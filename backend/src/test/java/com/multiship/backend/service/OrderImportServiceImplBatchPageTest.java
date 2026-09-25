package com.multiship.backend.service;

import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regressions found driving the batch page (/bulk/batches/:id) end to end on
 * 2026-09-23: one test per fix, each fails on the code before it.
 */
class OrderImportServiceImplBatchPageTest {

    private OrderImportServiceImpl service;
    private ImportBatchRepository importBatchRepository;
    private CarrierService carrierService;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        service = new OrderImportServiceImpl(carrierService);
        importBatchRepository = mock(ImportBatchRepository.class);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
        ReflectionTestUtils.setField(service, "importObjectMapper", mapper);
        when(importBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A saved CSV import (rows live in rowsJson) the repository hands back for id 121. */
    private ImportBatch savedBatch(String status, List<OrderImportRowDTO> rows) throws Exception {
        ImportBatch b = new ImportBatch();
        b.setId(121L);
        b.setStatus(status);
        b.setSource("BULK");
        b.setRowsJson(mapper.writeValueAsString(rows));
        when(importBatchRepository.findById(121L)).thenReturn(Optional.of(b));
        return b;
    }

    /** A valid domestic row, labelled or not. */
    private static OrderImportRowDTO shipRow(int n, Integer orderNo, String status) {
        OrderImportRowDTO r = OrderImportRowDTO.builder().rowNumber(n).orderRef("REF-" + n)
                .clientCode("ACME").recipientName("Jane " + n).recipientPhone("2125550100")
                .addressLine1(n + " Broadway").city("New York").state("NY").postalCode("10001").countryCode("US")
                .carrierCode("STAMPS").accountNumber("A12345").weight(new java.math.BigDecimal("1.5")).weightUnit("LB")
                .generatedOrderNo(orderNo).generatedStatus(status).build();
        r.setErrors(new ArrayList<>());
        r.setWarnings(new ArrayList<>());
        return r;
    }

    private static OrderImportRowDTO row(int n, String ref, Integer orderNo, String status) {
        OrderImportRowDTO r = OrderImportRowDTO.builder().rowNumber(n).orderRef(ref)
                .generatedOrderNo(orderNo).generatedStatus(status).build();
        r.setWarnings(new ArrayList<>());
        return r;
    }

    // ── Fix: a labelled row re-validated after an edit was warned that its
    //    own order was a duplicate of itself. ────────────────────────────────

    @Test
    void duplicateAdvisorySkipsTheRowsOwnLabelledOrder() {
        when(importBatchRepository.findAllByDeletedAtIsNullOrderByIdDesc()).thenReturn(List.of());
        when(importBatchRepository.findGeneratedOrdersByCustomerRefIn(any()))
                .thenReturn(List.<Object[]>of(new Object[] { 906976, "INTL-01-FR" }, new Object[] { 777, "INTL-02-DE" }));
        OrderImportRowDTO own = row(1, "INTL-01-FR", 906976, "GENERATED");
        OrderImportRowDTO other = row(2, "INTL-02-DE", null, null);

        service.flagOrderRefsAlreadyGenerated(List.of(own, other));

        assertTrue(own.getWarnings().isEmpty(), "a row's own order is not a duplicate: " + own.getWarnings());
        assertEquals(1, other.getWarnings().size(), "a live order under another reference still warns");
        assertTrue(other.getWarnings().get(0).contains("#777"));
    }

    // ── Fix: editing a cell bumped completedAt ("completed just now", and a
    //    "took" measured from the last run's start). ─────────────────────────

    @Test
    void editingARowKeepsTheLastRunsCompletedAt() throws Exception {
        LocalDateTime lastRun = LocalDateTime.of(2026, 9, 23, 17, 27, 20);
        ImportBatch batch = savedBatch("PARTIAL_COMPLETE",
                List.of(shipRow(1, 906976, "GENERATED"), shipRow(13, 906981, "FAILED")));
        batch.setGenerationStartedAt(lastRun);
        batch.setCompletedAt(lastRun.plusSeconds(3));

        ImportBatchDTO dto = service.updateBatchRowJson(121L, 13, "{\"weight\": 4}", "alice");

        assertEquals(lastRun.plusSeconds(3), batch.getCompletedAt(), "an edit is not a label run");
        assertEquals(lastRun.plusSeconds(3).toString(), dto.getCompletedAt());
    }

    // ── Fix: Retry on one row stamped completedAt but kept the last full
    //    run's generationStartedAt, so the header read "took 4m 14s". ────────

    @Test
    void retryingOneRowStartsItsOwnRunClock() throws Exception {
        LocalDateTime lastRun = LocalDateTime.of(2026, 9, 23, 17, 27, 20);
        ImportBatch batch = savedBatch("PARTIAL_COMPLETE",
                List.of(shipRow(1, 906976, "GENERATED"), shipRow(13, 7003, "FAILED")));
        batch.setGenerationStartedAt(lastRun);
        batch.setCompletedAt(lastRun.plusSeconds(3));
        when(carrierService.generateManualLabel(any(), any(), any())).thenReturn(
                com.multiship.backend.dto.ApiResponse.<com.multiship.backend.dto.LabelGenerationResponse>builder()
                        .status("success").code(200).message("ok")
                        .data(com.multiship.backend.dto.LabelGenerationResponse.builder()
                                .orderNo(7003L).trackingNumber("9400-TN-7003").status("GENERATED").build())
                        .build());

        service.generateLabelForRow(121L, 13, "alice", false);

        assertTrue(batch.getGenerationStartedAt().isAfter(lastRun), "the row's retry starts its own run clock");
        assertNotNull(batch.getCompletedAt());
        assertFalse(batch.getCompletedAt().isBefore(batch.getGenerationStartedAt()), "took = completed - started, never negative");
    }

    // ── Flow: import → validate → save allots the batch number, labelled or not.
    //    It used to reach the saved import only at Generate. ─────────────────
    @Test
    void savingAnImportAllotsTheBatchNumberTheUploadStamped() {
        OrderImportRowDTO r1 = shipRow(1, null, null);
        OrderImportRowDTO r2 = shipRow(2, null, null);
        r1.setBatchId(161);
        r2.setBatchId(161);
        org.mockito.ArgumentCaptor<ImportBatch> saved = org.mockito.ArgumentCaptor.forClass(ImportBatch.class);

        service.save(new ArrayList<>(List.of(r1, r2)), "alice", "acme.csv", true, true);

        org.mockito.Mockito.verify(importBatchRepository).save(saved.capture());
        assertEquals(161, saved.getValue().getLabelBatchId(), "the upload's batch number, before any label");
    }

    @Test
    void savingRowsWithNoBatchNumberMintsOne() {
        com.multiship.backend.repository.OrderRepository orders = mock(com.multiship.backend.repository.OrderRepository.class);
        when(orders.nextLabelBatchNumber()).thenReturn(900L);
        ReflectionTestUtils.setField(service, "orderRepository", orders);
        OrderImportRowDTO r1 = shipRow(1, null, null);
        org.mockito.ArgumentCaptor<ImportBatch> saved = org.mockito.ArgumentCaptor.forClass(ImportBatch.class);

        service.save(new ArrayList<>(List.of(r1)), "alice", "acme.csv", true, true);

        org.mockito.Mockito.verify(importBatchRepository).save(saved.capture());
        assertEquals(900, saved.getValue().getLabelBatchId());
        assertEquals(900, r1.getBatchId(), "the rows carry it too, so Generate keeps the same number");
    }

    // ── Paged rows: the batch page reads one page, not the whole batch ──────
    @Test
    void aPageOfRowsIsFilteredSearchedAndCountedByTheServer() throws Exception {
        OrderImportRowDTO labelled = shipRow(1, 906976, "GENERATED");
        OrderImportRowDTO broken = shipRow(2, null, null);
        broken.setOrderRef("REF-3");
        broken.setErrors(new ArrayList<>(List.of("recipientPhone is required")));
        OrderImportRowDTO sameOrder = shipRow(3, null, null);          // clean line of REF-3's order
        OrderImportRowDTO failed = shipRow(4, 7004, "FAILED");
        OrderImportRowDTO ready = shipRow(5, null, null);
        ready.setCity("Austin");
        savedBatch("PARTIAL_COMPLETE", List.of(labelled, broken, sameOrder, failed, ready));

        var all = service.historyRows(121L, "all", null, null, 0, 2);
        assertEquals(5, all.getAll());
        assertEquals(5, all.getTotal());
        assertEquals(List.of(1, 2), all.getRows().stream().map(OrderImportRowDTO::getRowNumber).toList(), "page 1 of 2 rows");
        assertEquals(2, all.getAttention(), "the broken row and the failed label");
        assertEquals(4, all.getPending(), "everything but the live label");
        assertEquals(List.of("ACME"), all.getClientCodes());
        assertEquals("/api/v1/orders/906976/label/pdf", all.getRows().get(0).getLabelUrl());

        var page2 = service.historyRows(121L, "all", null, null, 1, 2);
        assertEquals(List.of(3, 4), page2.getRows().stream().map(OrderImportRowDTO::getRowNumber).toList());
        assertEquals(2, page2.getRows().get(0).getOrderBlockedBy(), "row 3 waits for row 2, the broken line of its order");

        assertEquals(List.of(2, 4), service.historyRows(121L, "attention", null, null, 0, 50).getRows().stream()
                .map(OrderImportRowDTO::getRowNumber).toList());
        var found = service.historyRows(121L, "all", "austin", null, 0, 50);
        assertEquals(1, found.getTotal());
        assertEquals(5, found.getRows().get(0).getRowNumber());
        assertEquals(5, found.getAll(), "the view counts stay the batch's, whatever the search");
        assertEquals(3, service.historyRows(121L, "attention", null, 3, 0, 50).getRows().get(0).getRowNumber(),
                "one row by number, whatever the view");
        assertNull(service.historyRows(999L, "all", null, null, 0, 50));
    }

    // ── Loophole: commit cleared every row's errors and processGroup re-checked
    //    only the order's first row, so a broken line 2 shipped with it. ─────
    @Test
    void aBrokenSecondLineBlocksTheWholeOrder() {
        OrderImportRowDTO leader = shipRow(1, null, null);
        OrderImportRowDTO line2 = shipRow(2, null, null);
        line2.setOrderRef("REF-1");
        line2.setWeight(null);
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(leader, line2));

        service.commit(rows, "alice");

        org.mockito.Mockito.verify(carrierService, org.mockito.Mockito.never()).generateManualLabel(any(), any(), any());
        assertTrue(leader.getErrors().stream().anyMatch(e -> e.startsWith("row 2 of this order needs fixes")), leader.getErrors().toString());
        assertTrue(line2.getErrors().stream().anyMatch(e -> e.startsWith("weight must be > 0")), line2.getErrors().toString());
    }

    // ── Fix: an edit's / Validate all's answer carried the rows without their
    //    last-printed time, so every "Printed 23 Sept" caption vanished. ─────

    @Test
    void rowResponsesCarryLastPrintedLikeThePageLoad() throws Exception {
        savedBatch("PARTIAL_COMPLETE", List.of(shipRow(1, 906976, "GENERATED"), shipRow(13, 906981, "FAILED")));
        com.multiship.backend.service.printing.DocumentPrintLog printLog =
                mock(com.multiship.backend.service.printing.DocumentPrintLog.class);
        when(printLog.lastPrintedByOrder(any())).thenReturn(java.util.Map.of(906976, LocalDateTime.of(2026, 9, 23, 16, 23, 47)));
        ReflectionTestUtils.setField(service, "documentPrintLog", printLog);

        ImportBatchDTO validated = service.validateAllRows(121L, "alice");
        ImportBatchDTO edited = service.updateBatchRowJson(121L, 13, "{\"weight\": 4}", "alice");
        ImportBatchDTO loaded = service.historyDetail(121L);

        for (ImportBatchDTO dto : List.of(validated, edited, loaded)) {
            assertEquals("2026-09-23T16:23:47", dto.getRows().get(0).getLastPrintedAt(), "row 1 was printed");
            assertEquals(null, dto.getRows().get(1).getLastPrintedAt(), "row 13 never was");
        }
    }

    // ── Product call (2026-09-23): what is in Trash can be deleted directly —
    //    Empty Trash no longer keeps imports whose labels are still live. ────

    @Test
    void emptyTrashPurgesTrashedImportsEvenWithLiveLabels() throws Exception {
        ImportBatch trashed = new ImportBatch();
        trashed.setId(119L);
        trashed.setSource("BULK");
        trashed.setDeletedAt(LocalDateTime.of(2026, 9, 15, 15, 37));
        trashed.setRowsJson(mapper.writeValueAsString(List.of(shipRow(1, 906976, "GENERATED"))));
        when(importBatchRepository.findAllByDeletedAtIsNotNullOrderByIdDesc()).thenReturn(List.of(trashed));

        var result = service.purgeTrashChecked("e2etester");

        assertEquals(1, result.purged(), "a trashed import with a live label is purged too");
        assertEquals(0, result.keptWithLiveLabels());
        org.mockito.Mockito.verify(importBatchRepository).deleteAll(List.of(trashed));
    }
}
