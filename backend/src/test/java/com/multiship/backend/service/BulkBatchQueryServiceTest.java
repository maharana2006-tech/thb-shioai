package com.multiship.backend.service;

import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.model.ImportBatch;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk Mailer's server-side batch list. The filtering, sorting and paging
 * run in Postgres (checked against the live database when this shipped);
 * these cover the parts that are plain Java.
 */
class BulkBatchQueryServiceTest {

    @Test
    void aListEntryCarriesTheBatchButNeverItsRows() {
        ImportBatch b = new ImportBatch();
        b.setId(121L);
        b.setFileName("acme_sept.csv");
        b.setStatus("INITIATE");
        b.setCreatedAt(LocalDateTime.of(2026, 9, 22, 10, 0));
        b.setTotalRows(40);
        b.setSavedRows(38);
        b.setInvalidRows(2);
        b.setRowsJson("[{\"clientCode\":\"ACME\"}]");

        ImportBatchDTO dto = BulkBatchQueryService.summaryOf(b);

        assertEquals(121L, dto.getId());
        assertEquals("acme_sept.csv", dto.getFileName());
        assertEquals(2, dto.getInvalidRows());
        assertEquals("BULK", dto.getSource(), "a file batch with no source reads as BULK");
        assertEquals("AUTO", dto.getBillingMode());
        assertNull(dto.getRows(), "the list never ships rows");
    }

    /** rows_json can be megabytes per batch — the list query must not select it. */
    @Test
    void theListQueryNeverSelectsTheRowsColumn() {
        assertFalse(BulkBatchQueryService.LIST_COLUMNS.contains("rowsJson"));
        assertEquals(21, BulkBatchQueryService.LIST_COLUMNS.size());
        assertTrue(BulkBatchQueryService.LIST_COLUMNS.contains("slug"), "the list links to each batch by its slug");
    }

    /** "18 generated · 2 pending · 1 voided": stored counts, less the rows of orders voided since. */
    @Test
    void labelCountsTakeLiveVoidsIntoAccountInRows() {
        com.multiship.backend.repository.OrderTrackingRepository tracking =
                org.mockito.Mockito.mock(com.multiship.backend.repository.OrderTrackingRepository.class);
        com.multiship.backend.model.OrderTracking voided = new com.multiship.backend.model.OrderTracking();
        voided.setOrderNo(5002);
        voided.setStatus("VOIDED");
        org.mockito.Mockito.when(tracking.findByOrderNoIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.List.of(voided));
        BulkBatchQueryService service = new BulkBatchQueryService(null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "trackingRepository", tracking);

        jakarta.persistence.Tuple t = org.mockito.Mockito.mock(jakarta.persistence.Tuple.class);
        org.mockito.Mockito.when(t.get("labelOrders", String.class)).thenReturn("{\"5001\":2,\"5002\":3}");
        org.mockito.Mockito.when(t.get("labelsGenerated", Integer.class)).thenReturn(5);
        org.mockito.Mockito.when(t.get("labelsFailed", Integer.class)).thenReturn(1);
        org.mockito.Mockito.when(t.get("labelsCounted", Boolean.class)).thenReturn(true);
        ImportBatchDTO d = ImportBatchDTO.builder().id(1L).totalRows(10).build();

        service.applyLabelCounts(java.util.List.of(t), java.util.List.of(d));

        assertEquals(2, d.getLabelsGenerated(), "5 generated rows, 3 of them (order 5002) voided since");
        assertEquals(3, d.getLabelsVoided());
        assertEquals(1, d.getLabelsFailed());
        assertEquals(4, d.getLabelsPending(), "10 rows − 5 generated − 1 failed");
    }

    @Test
    void theStoredCountsFollowTheRows() {
        com.multiship.backend.model.ImportBatch b = new com.multiship.backend.model.ImportBatch();
        java.util.List<com.multiship.backend.dto.OrderImportRowDTO> rows = java.util.List.of(
                com.multiship.backend.dto.OrderImportRowDTO.builder().rowNumber(1).generatedOrderNo(7).generatedStatus("GENERATED").build(),
                com.multiship.backend.dto.OrderImportRowDTO.builder().rowNumber(2).generatedOrderNo(7).generatedStatus("GENERATED").build(),
                com.multiship.backend.dto.OrderImportRowDTO.builder().rowNumber(3).generatedOrderNo(8).generatedStatus("QUEUED_USPS").build(),
                com.multiship.backend.dto.OrderImportRowDTO.builder().rowNumber(4).generatedStatus("FAILED").build(),
                com.multiship.backend.dto.OrderImportRowDTO.builder().rowNumber(5).build());
        OrderImportServiceImpl.stampLabelCounts(b, rows, new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(3, b.getLabelsGenerated());
        assertEquals(1, b.getLabelsFailed());
        assertEquals(java.util.Map.of(7, 2, 8, 1), BulkBatchQueryService.parseLabelOrders(b.getLabelOrders()));
    }
}
