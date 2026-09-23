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

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new OrderImportServiceImpl(mock(CarrierService.class));
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
}
