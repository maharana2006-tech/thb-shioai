package com.multiship.backend.service;

import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

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

    @BeforeEach
    void setUp() {
        service = new OrderImportServiceImpl(mock(CarrierService.class));
        importBatchRepository = mock(ImportBatchRepository.class);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
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
}
