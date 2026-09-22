package com.multiship.backend.service;

import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.model.ImportBatch;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(16, BulkBatchQueryService.LIST_COLUMNS.size());
    }
}
