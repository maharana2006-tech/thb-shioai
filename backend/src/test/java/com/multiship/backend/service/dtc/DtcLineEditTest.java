package com.multiship.backend.service.dtc;

import com.multiship.backend.dto.DtcOrderEditRequest;
import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
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
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batch page's Edit on a line that has no label order yet: what the next
 * Automatic label run builds from. It must only touch this tenant's batch, never a
 * line that has (or is buying) a label, and must keep patch semantics.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DtcLineEditTest {

    @Mock DtcOrderRepository dtcOrderRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderTrackingRepository orderTrackingRepository;
    @Mock ShipmentBatchRepository shipmentBatchRepository;

    @InjectMocks DtcLabelGenerationService service;

    private static final BigDecimal BATCH = new BigDecimal("245");

    private DtcOrder line(String status) {
        DtcOrder row = new DtcOrder();
        row.setId(11L);
        row.setTenantId("ARHDEV");
        row.setBatchId(BATCH);
        row.setToteNumber("T-004");
        row.setShipName("Ava Chen");
        row.setShipAddr1("PO BOX");
        row.setShipAttn("Old Co");
        row.setGeneratedStatus(status);
        row.setGeneratedMessage(status == null ? null : "row is missing a usable ship-to address");
        when(dtcOrderRepository.findById(11L)).thenReturn(Optional.of(row));
        when(dtcOrderRepository.save(any(DtcOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        return row;
    }

    private static DtcOrderEditRequest edit(String addr1, String attn, String country, BigDecimal weight) {
        return new DtcOrderEditRequest(null, attn, addr1, null, null, null, null, null, country,
                null, null, weight, null, null, null, null);
    }

    @Test
    void patchesOnlyWhatWasSentAndClearsBlanks() {
        DtcOrder row = line("FAILED");

        DtcOrder saved = service.editLine("ARHDEV", BATCH, 11L, edit("233 S Wacker Dr", "", "us", new BigDecimal("2.5")), "alice");

        assertEquals("233 S Wacker Dr", saved.getShipAddr1());
        assertNull(saved.getShipAttn(), "blank clears");
        assertEquals("Ava Chen", saved.getShipName(), "null keeps");
        assertEquals("US", saved.getShipToCountryCode());
        assertEquals(new BigDecimal("2.5"), saved.getWeight());
        assertEquals("FAILED", saved.getGeneratedStatus(), "still retried by the next run");
        assertTrue(saved.getGeneratedMessage().startsWith("Edited by alice"));
        assertEquals(row, saved);
    }

    @Test
    void refusesALineFromAnotherTenantOrBatch() {
        line(null);
        assertThrows(NoSuchElementException.class,
                () -> service.editLine("OTHER", BATCH, 11L, edit("x", null, null, null), "alice"));
        assertThrows(NoSuchElementException.class,
                () -> service.editLine("ARHDEV", new BigDecimal("246"), 11L, edit("x", null, null, null), "alice"));
        verify(dtcOrderRepository, never()).save(any());
    }

    @Test
    void refusesALineThatHasOrIsBuyingALabel() {
        for (String status : List.of("GENERATED", "QUEUED_USPS", DtcLabelGenerationService.STATUS_IN_FLIGHT)) {
            line(status);
            assertThrows(IllegalStateException.class,
                    () -> service.editLine("ARHDEV", BATCH, 11L, edit("x", null, null, null), "alice"), status);
        }
        verify(dtcOrderRepository, never()).save(any());
    }

    @Test
    void rejectsValuesTheLabelCannotUse() {
        line(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.editLine("ARHDEV", BATCH, 11L, edit(null, null, "USA", null), "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> service.editLine("ARHDEV", BATCH, 11L, edit(null, null, null, BigDecimal.ZERO), "alice"));
    }

    @Test
    void linksAHandLabelledOrderOfTheSameClient() {
        DtcOrder row = line("FAILED");
        Order order = new Order();
        order.setOrderNo(906982);
        order.setTenantId("ARHDEV");
        order.setOrderStatus("GENERATED");
        order.setIsError(false);
        OrderTracking tracking = new OrderTracking();
        tracking.setOrderNo(906982);
        tracking.setTrackingNumber("1Z999AA10000000001");
        when(orderRepository.findByOrderNo(906982)).thenReturn(Optional.of(order));
        when(orderTrackingRepository.findByOrderNo(906982)).thenReturn(Optional.of(tracking));
        when(shipmentBatchRepository.findByOrderNoOrderByBatchSeqAsc(906982)).thenReturn(List.of());

        service.editLine("ARHDEV", BATCH, 11L, new DtcOrderEditRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 906982), "alice");

        assertEquals("GENERATED", row.getGeneratedStatus());
        assertEquals(906982, row.getGeneratedOrderNo());
        assertEquals("1Z999AA10000000001", row.getGeneratedTrackingNumber());
    }

    @Test
    void wontLinkAnotherClientsOrder() {
        DtcOrder row = line("FAILED");
        Order order = new Order();
        order.setOrderNo(906982);
        order.setTenantId("ACME");
        when(orderRepository.findByOrderNo(906982)).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class, () -> service.editLine("ARHDEV", BATCH, 11L,
                new DtcOrderEditRequest(null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, 906982), "alice"));
        assertNull(row.getGeneratedOrderNo());
    }
}
