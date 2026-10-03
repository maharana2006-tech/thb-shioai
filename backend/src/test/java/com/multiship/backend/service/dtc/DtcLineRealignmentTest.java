package com.multiship.backend.service.dtc;

import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShipmentBatch;
import com.multiship.backend.repository.DtcGenerationJobRepository;
import com.multiship.backend.repository.DtcOrderRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipmentBatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DtcLabelGenerationService#syncFromLabel}. A line repaired on
 * the manual shipment form is labelled by a path that never touches dtc_orders, so the
 * line would go on reporting the carrier error the worker last saw — and the next batch
 * run would re-attempt it from the ERP row, overwriting the repair. These tests pin the
 * realignment and, just as importantly, the cases where the line must NOT move.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DtcLineRealignmentTest {

    @Mock DtcGenerationJobRepository jobRepository;
    @Mock DtcOrderRepository dtcOrderRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderTrackingRepository orderTrackingRepository;
    @Mock ShipmentBatchRepository shipmentBatchRepository;

    @InjectMocks DtcLabelGenerationService service;

    private static final int LABEL_ORDER = 900037;

    private DtcOrder line(String generatedStatus) {
        DtcOrder row = new DtcOrder();
        row.setId(1L);
        row.setBatchId(new BigDecimal("245"));
        row.setTenantId("ARHDEV");
        row.setGeneratedStatus(generatedStatus);
        row.setGeneratedOrderNo(LABEL_ORDER);
        row.setGeneratedMessage("121100 The requested service is invalid for the shipment origin");
        return row;
    }

    private Order order(String status, boolean isError) {
        Order order = new Order();
        order.setOrderNo(LABEL_ORDER);
        order.setOrderStatus(status);
        order.setIsError(isError);
        return order;
    }

    private OrderTracking tracking(String status, String trackingNumber) {
        OrderTracking tracking = new OrderTracking();
        tracking.setOrderNo(LABEL_ORDER);
        tracking.setStatus(status);
        tracking.setTrackingNumber(trackingNumber);
        tracking.setWarehouseCode("746W05");
        return tracking;
    }

    private void givenOrderAndLabel(Order order, OrderTracking tracking) {
        when(orderRepository.findByOrderNo(LABEL_ORDER)).thenReturn(Optional.of(order));
        when(orderTrackingRepository.findByOrderNo(LABEL_ORDER)).thenReturn(Optional.of(tracking));
        when(shipmentBatchRepository.findByOrderNoOrderByBatchSeqAsc(LABEL_ORDER))
                .thenReturn(List.of(ShipmentBatch.builder().carrierCode("UPS").build()));
    }

    @Test
    void realignsARepairedLineToItsGeneratedLabel() {
        DtcOrder row = line("FAILED");
        givenOrderAndLabel(order("GENERATED", false), tracking("GENERATED", "1ZREPAIRED"));

        assertTrue(service.syncFromLabel(row), "the line now carries a label — the batch run must skip it");
        assertEquals("GENERATED", row.getGeneratedStatus());
        assertEquals("1ZREPAIRED", row.getGeneratedTrackingNumber());
        assertEquals("UPS", row.getGeneratedCarrierCode());
        assertEquals(Integer.valueOf(LABEL_ORDER), row.getGeneratedOrderNo());
        assertEquals("Manual shipment #" + LABEL_ORDER + " labelled on 746W05.", row.getGeneratedMessage());
        verify(dtcOrderRepository).save(row);
    }

    @Test
    void anAlreadyGeneratedLineIsSkippedWithoutLookingAnythingUp() {
        DtcOrder row = line("GENERATED");

        assertTrue(service.syncFromLabel(row));
        verifyNoInteractions(orderRepository, orderTrackingRepository, shipmentBatchRepository, dtcOrderRepository);
    }

    @Test
    void aLineWithNoLabelOrderIsLeftAlone() {
        DtcOrder row = line(null);
        row.setGeneratedOrderNo(null);

        assertFalse(service.syncFromLabel(row));
        assertNull(row.getGeneratedStatus());
        verify(dtcOrderRepository, never()).save(any());
    }

    @Test
    void aLineStillInErrorOnTheCarrierSideDoesNotMove() {
        DtcOrder row = line("FAILED");
        givenOrderAndLabel(order("ERROR", true), tracking("ERROR", null));

        assertFalse(service.syncFromLabel(row));
        assertEquals("FAILED", row.getGeneratedStatus());
        verify(dtcOrderRepository, never()).save(any());
    }

    @Test
    void aVoidedLabelDoesNotDragItsLineBackToFailed() {
        DtcOrder row = line("GENERATED");
        row.setGeneratedTrackingNumber("1ZBOUGHT");

        assertTrue(service.syncFromLabel(row), "a bought-then-voided label must not be re-bought");
        verifyNoInteractions(orderRepository);
        assertEquals("1ZBOUGHT", row.getGeneratedTrackingNumber());
    }

    @Test
    void aGeneratedOrderWithoutALabelYetLeavesTheLineAsItIs() {
        DtcOrder row = line("QUEUED_USPS");
        givenOrderAndLabel(order("GENERATED", false), tracking("GENERATED", " "));

        assertFalse(service.syncFromLabel(row));
        assertEquals("QUEUED_USPS", row.getGeneratedStatus());
        verify(dtcOrderRepository, never()).save(any());
    }
}
