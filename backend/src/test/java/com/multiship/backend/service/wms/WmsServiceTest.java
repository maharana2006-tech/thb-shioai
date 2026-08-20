package com.multiship.backend.service.wms;

import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.dto.wms.WmsShippableOrderDTO;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link WmsService}. The service was direct-pushed
 * with zero tests. Every branch of {@link WmsService#pullShippable} is
 * exercised: unconfigured no-op, blank/null externalId → failed,
 * existing externalId → skipped (idempotent re-pull), successful save →
 * imported, save exception → failed. The {@code toOrder} mapping is
 * verified through the successful-import path via an ArgumentCaptor
 * on {@code orderRepository.save}.
 */
class WmsServiceTest {

    private WmsClient wmsClient;
    private OrderRepository orderRepository;
    private WmsService service;

    @BeforeEach
    void setUp() {
        wmsClient = mock(WmsClient.class);
        orderRepository = mock(OrderRepository.class);
        service = new WmsService(wmsClient, orderRepository);
    }

    private WmsShippableOrderDTO sample(String externalId, String clientCode) {
        WmsShippableOrderDTO dto = new WmsShippableOrderDTO();
        dto.setExternalId(externalId);
        dto.setClientCode(clientCode);
        dto.setRecipientName("Jane Recipient");
        dto.setRecipientCompany("Recipient Co.");
        dto.setAddressLine1("42 Overseas Ave");
        dto.setCity("London");
        dto.setState("");
        dto.setPostalCode("SW1A 1AA");
        dto.setCountryCode("GB");
        dto.setCarrierCode("UPS");
        dto.setServiceType("UPS_WS");
        dto.setWeight(2.5);
        dto.setReference("PO-9876");
        return dto;
    }

    // ===== isConfigured — pure delegation =====

    @Test
    void isConfigured_delegatesToClient_true() {
        when(wmsClient.isConfigured()).thenReturn(true);
        assertTrue(service.isConfigured());
    }

    @Test
    void isConfigured_delegatesToClient_false() {
        when(wmsClient.isConfigured()).thenReturn(false);
        assertFalse(service.isConfigured());
    }

    // ===== not-configured no-op =====

    @Test
    void pullShippable_notConfigured_returnsNoOp_withInstructionalMessage() {
        when(wmsClient.isConfigured()).thenReturn(false);

        WmsPullResultDTO result = service.pullShippable("alice");

        assertFalse(result.isConfigured());
        assertTrue(result.getMessages().get(0).contains("WMS_BASE_URL"),
                "message must tell the operator how to enable the pull");
        assertTrue(result.getImportedOrderNos().isEmpty());
        // Critical: no WMS fetch when unconfigured — the client's mock
        // fetchShippable would return an empty list even if called, but we
        // want to prove the guard SHORT-CIRCUITS before the HTTP call.
        verify(wmsClient, never()).fetchShippable();
        verify(orderRepository, never()).save(any());
    }

    // ===== empty WMS list =====

    @Test
    void pullShippable_emptyList_reportsZeroCounts_noSaves() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of());

        WmsPullResultDTO result = service.pullShippable("alice");

        assertTrue(result.isConfigured());
        assertEquals(0, result.getFetched());
        assertEquals(0, result.getImported());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
        verify(orderRepository, never()).save(any());
    }

    // ===== bad-input branches =====

    @Test
    void pullShippable_blankExternalId_countsAsFailed_withMessage() {
        WmsShippableOrderDTO bad = sample("   ", "ACME");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(bad));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getFetched());
        assertEquals(0, result.getImported());
        assertEquals(1, result.getFailed());
        assertTrue(result.getMessages().get(0).contains("no externalId"));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void pullShippable_nullDto_countsAsFailed_withoutNullPointer() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(java.util.Arrays.asList((WmsShippableOrderDTO) null));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getFailed(),
                "a null entry in the WMS list must be counted, not thrown on");
        verify(orderRepository, never()).save(any());
    }

    // ===== idempotent re-pull =====

    @Test
    void pullShippable_alreadyImported_countsAsSkipped_noSave() {
        WmsShippableOrderDTO existing = sample("WMS-EXT-1", "ACME");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(existing));
        when(orderRepository.existsByWmsExternalId("WMS-EXT-1")).thenReturn(true);

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getFetched());
        assertEquals(0, result.getImported());
        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getFailed());
        verify(orderRepository, never()).save(any());
    }

    // ===== successful import + toOrder mapping =====

    @Test
    void pullShippable_newOrder_saves_countsAsImported_andReturnsOrderNo() {
        WmsShippableOrderDTO fresh = sample("WMS-EXT-99", "ACME");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(fresh));
        when(orderRepository.existsByWmsExternalId("WMS-EXT-99")).thenReturn(false);
        when(orderRepository.nextManualOrderNo()).thenReturn(70001);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order arg = inv.getArgument(0);
            arg.setOrderNo(arg.getOrderNo() != null ? arg.getOrderNo() : 70001);
            return arg;
        });

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getImported());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
        assertEquals(List.of(70001), result.getImportedOrderNos());
    }

    @Test
    void toOrder_mapsAllFields_fromDtoOntoTheEntity() {
        WmsShippableOrderDTO fresh = sample("WMS-42", "ACME");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(fresh));
        when(orderRepository.existsByWmsExternalId("WMS-42")).thenReturn(false);
        when(orderRepository.nextManualOrderNo()).thenReturn(70002);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pullShippable("alice");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        Order order = saved.getValue();

        assertEquals(70002, order.getOrderNo());
        assertEquals(0, order.getOrderSuffix());
        assertEquals("PENDING", order.getOrderStatus(),
                "WMS-imported orders start PENDING — they still need labels generated");
        assertEquals("WMS", order.getSource());
        assertEquals("N", order.getIsManual());
        assertEquals("N", order.getIsReturn());
        assertEquals("WMS-42", order.getWmsExternalId());
        assertEquals("ACME", order.getCustNo());
        assertEquals("ACME", order.getTenantId());
        assertEquals("Jane Recipient", order.getShipName());
        assertEquals("Recipient Co.", order.getShipAttn());
        assertEquals("42 Overseas Ave", order.getShipAddr1());
        assertEquals("London", order.getShiptoCity());
        assertEquals("SW1A 1AA", order.getShiptoZip());
        assertEquals("GB", order.getShiptoCountryCd());
        assertEquals("PO-9876", order.getGoodsDesc());
        assertEquals(0, order.getWeight().compareTo(new java.math.BigDecimal("2.5")));
    }

    @Test
    void toOrder_shipviaCd_prefersServiceType_overCarrierCode() {
        WmsShippableOrderDTO dto = sample("WMS-A", "ACME");
        // Both set; serviceType wins.
        dto.setCarrierCode("UPS");
        dto.setServiceType("UPS_NEXT_DAY");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(dto));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pullShippable("alice");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertEquals("UPS_NEXT_DAY", saved.getValue().getShipviaCd(),
                "serviceType is more specific than carrierCode — prefer it");
    }

    @Test
    void toOrder_shipviaCd_fallsBackToCarrierCode_whenServiceTypeBlank() {
        WmsShippableOrderDTO dto = sample("WMS-B", "ACME");
        dto.setCarrierCode("UPS");
        dto.setServiceType("");   // blank → fall through to carrierCode
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(dto));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pullShippable("alice");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertEquals("UPS", saved.getValue().getShipviaCd());
    }

    @Test
    void toOrder_clientCodeBlank_stillProduces_orderWithFallbackCustNo() {
        WmsShippableOrderDTO dto = sample("WMS-C", "");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(dto));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pullShippable("alice");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        Order o = saved.getValue();
        assertEquals("WMS", o.getCustNo(),
                "custNo falls back to 'WMS' when the WMS omits clientCode");
        assertNull(o.getTenantId(),
                "tenantId stays null when clientCode is blank — no phantom scope");
    }

    // ===== per-row failure isolation =====

    @Test
    void pullShippable_saveException_countsAsFailed_withMessage_andDoesNotKillJob() {
        WmsShippableOrderDTO row1 = sample("WMS-OK-1", "ACME");
        WmsShippableOrderDTO row2 = sample("WMS-BOOM", "ACME");
        WmsShippableOrderDTO row3 = sample("WMS-OK-2", "ACME");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(row1, row2, row3));
        when(orderRepository.existsByWmsExternalId(any())).thenReturn(false);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order arg = inv.getArgument(0);
            if ("WMS-BOOM".equals(arg.getWmsExternalId())) {
                throw new RuntimeException("DB unique violation simulated");
            }
            return arg;
        });

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(3, result.getFetched());
        assertEquals(2, result.getImported(), "row1 + row3 survive");
        assertEquals(0, result.getSkipped());
        assertEquals(1, result.getFailed());
        assertTrue(result.getMessages().stream().anyMatch(m -> m.contains("WMS-BOOM")));
        assertTrue(result.getMessages().stream().anyMatch(m -> m.contains("DB unique violation")));
    }

    // ===== mixed batch summary =====

    @Test
    void pullShippable_mixedBatch_reportsAccurateCounts() {
        WmsShippableOrderDTO existing = sample("WMS-E1", "ACME");
        WmsShippableOrderDTO fresh1 = sample("WMS-N1", "ACME");
        WmsShippableOrderDTO badId = sample("", "ACME");
        WmsShippableOrderDTO fresh2 = sample("WMS-N2", "ACME");
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(existing, fresh1, badId, fresh2));
        when(orderRepository.existsByWmsExternalId("WMS-E1")).thenReturn(true);
        when(orderRepository.existsByWmsExternalId("WMS-N1")).thenReturn(false);
        when(orderRepository.existsByWmsExternalId("WMS-N2")).thenReturn(false);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(4, result.getFetched());
        assertEquals(2, result.getImported(), "N1 + N2");
        assertEquals(1, result.getSkipped(), "E1");
        assertEquals(1, result.getFailed(), "blank externalId");
        // Save called exactly for the two importable rows — never for the
        // existing (already-imported) row.
        verify(orderRepository, never()).save(
                org.mockito.ArgumentMatchers.<Order>argThat(o -> "WMS-E1".equals(o.getWmsExternalId())));
    }
}
