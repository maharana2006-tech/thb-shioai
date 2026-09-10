package com.multiship.backend.service;

import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-09-10 load test: the batch was saved while ~8 workers were still labelling,
 * so those rows carried no order number and a Retry labelled the orders again.
 * A Retry must now attach such rows to the order already in the label batch.
 */
class OrderImportRetryMatchTest {

    private ImportBatchRepository repo;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ImportBatchRepository.class);
        service = new OrderImportServiceImpl(mock(CarrierService.class));
        ReflectionTestUtils.setField(service, "importBatchRepository", repo);
    }

    private static OrderImportRowDTO row(int n, String orderRef, String reference, String status) {
        OrderImportRowDTO r = new OrderImportRowDTO();
        r.setRowNumber(n);
        r.setOrderRef(orderRef);
        r.setReference(reference);
        r.setGeneratedStatus(status);
        return r;
    }

    @Test
    void rowWhoseOrderLandedAfterTheSaveIsAttachedNotResent() {
        OrderImportRowDTO lead = row(1, "LT0910-0190", "PO-LT0910-0190", null);
        OrderImportRowDTO item = row(2, "LT0910-0190", "PO-LT0910-0190", null);
        OrderImportRowDTO other = row(3, "LT0910-0199", "PO-LT0910-0199", null);
        List<Object[]> hits = new ArrayList<>();
        hits.add(new Object[]{901620, "PO-LT0910-0190", "GENERATED"});
        when(repo.findOrdersInLabelBatchByCustomerRefIn(eq(51), anyCollection())).thenReturn(hits);

        service.syncRowsWithLiveOrders(List.of(lead, item, other), 51);

        assertEquals(Integer.valueOf(901620), lead.getGeneratedOrderNo());
        assertEquals("GENERATED", lead.getGeneratedStatus());
        assertEquals(Integer.valueOf(901620), item.getGeneratedOrderNo(), "item-line rows of the order are attached too");
        assertEquals("GENERATED", item.getGeneratedStatus());
        assertNull(other.getGeneratedOrderNo(), "an order that was never created stays pending");
        assertNull(other.getGeneratedStatus());
    }

    @Test
    void failedOrderIsAttachedSoRetryReusesIt() {
        OrderImportRowDTO lead = row(1, "LT0910-0090", "PO-LT0910-0090", null);
        List<Object[]> hits = new ArrayList<>();
        hits.add(new Object[]{901519, "PO-LT0910-0090", "ERROR"});
        when(repo.findOrdersInLabelBatchByCustomerRefIn(eq(51), anyCollection())).thenReturn(hits);

        service.syncRowsWithLiveOrders(List.of(lead), 51);

        assertEquals(Integer.valueOf(901519), lead.getGeneratedOrderNo());
        assertNull(lead.getGeneratedStatus(), "not labelled, so it is still sent, as the same order");
    }

    @Test
    void noLabelBatchMeansNoLookup() {
        service.syncRowsWithLiveOrders(List.of(row(1, "A-1", "PO-A-1", null)), null);
        verify(repo, never()).findOrdersInLabelBatchByCustomerRefIn(any(), anyCollection());
    }

    @Test
    void ordersSummaryCountsOrdersNotRows() {
        List<OrderImportRowDTO> rows = List.of(
                row(1, "A", null, "GENERATED"), row(2, "A", null, "GENERATED"), row(3, "A", null, "GENERATED"),
                row(4, "B", null, "FAILED"), row(5, "B", null, "FAILED"),
                row(6, "C", null, null));
        assertEquals("1 of 3 order(s) labelled · 1 failed", OrderImportServiceImpl.ordersSummary(rows));
    }
}
