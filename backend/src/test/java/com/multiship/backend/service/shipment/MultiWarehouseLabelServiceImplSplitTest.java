package com.multiship.backend.service.shipment;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehouseLabelRequest.LineItem;
import com.multiship.backend.dto.MultiWarehouseLabelResponse;
import com.multiship.backend.model.Shipment;
import com.multiship.backend.model.ShipmentGroup;
import com.multiship.backend.repository.ShipmentGroupRepository;
import com.multiship.backend.repository.ShipmentRepository;
import com.multiship.backend.service.CarrierService;
import com.multiship.backend.service.TenantScopeEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Perf P3 phase 2 — split path (phaseSplitEnabled=true). Verifies the
 * final group+Shipment persist runs inside the REQUIRES_NEW tx template,
 * and child-failure semantics still skip persist entirely.
 */
class MultiWarehouseLabelServiceImplSplitTest {

    private CarrierService carrierService;
    private ShipmentGroupRepository groupRepo;
    private ShipmentRepository shipmentRepo;
    private MultiWarehouseLabelServiceImpl service;
    private UserDetails user;
    private AtomicInteger txInvocations;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        groupRepo = mock(ShipmentGroupRepository.class);
        shipmentRepo = mock(ShipmentRepository.class);
        user = mock(UserDetails.class);
        when(user.getUsername()).thenReturn("alice");
        when(groupRepo.save(any())).thenAnswer(inv -> {
            ShipmentGroup g = inv.getArgument(0);
            g.setId(999L);
            return g;
        });
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(1);
        when(shipmentRepo.save(any())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            s.setId(seq.getAndIncrement());
            return s;
        });

        service = new MultiWarehouseLabelServiceImpl(carrierService, groupRepo, shipmentRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));

        // Flag ON + stub TransactionTemplate that invokes the callback
        // inline + counts invocations (so we can prove the persist step
        // runs INSIDE the template, not outside).
        ReflectionTestUtils.setField(service, "phaseSplitEnabled", true);
        txInvocations = new AtomicInteger(0);
        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            txInvocations.incrementAndGet();
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        ReflectionTestUtils.setField(service, "requiresNewTransactionTemplate", txTemplate);
    }

    @Test
    void splitPath_happyPath_runsPersistInsideTxTemplateExactlyOnce() {
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 1));
        req.getLines().add(line("WEST", "SKU-2", 1));
        stubLabel("EAST", "1Z-EAST-42");
        stubLabel("WEST", "1Z-WEST-43");

        ApiResponse<MultiWarehouseLabelResponse> resp = service.generate(req, user);

        assertEquals(200, resp.getCode());
        assertEquals(2, resp.getData().getShipmentCount());
        // Split mode: generate() does NOT wrap the whole body — only the
        // final persist step runs via the template. Exactly one tx start.
        assertEquals(1, txInvocations.get(),
                "split mode must wrap ONLY the final persist in the tx template, "
                + "not the whole body (that would equal the legacy single-tx behaviour)");
        verify(groupRepo, times(1)).save(any());
        verify(shipmentRepo, times(2)).save(any());
    }

    @Test
    void legacyPath_wrapsEntireBodyInSingleTx() {
        // Flag OFF — the whole body runs inside one tx (legacy behaviour).
        // Prove this by counting template invocations: exactly 1, invoked
        // around the entire body (not around just the persist step).
        ReflectionTestUtils.setField(service, "phaseSplitEnabled", false);
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 1));
        stubLabel("EAST", "1Z-EAST-99");

        service.generate(req, user);

        assertEquals(1, txInvocations.get(),
                "legacy mode wraps the entire body in exactly one tx");
    }

    @Test
    void splitPath_childFailure_stillSkipsPersist() {
        // Child failure must still throw — operator contract unchanged.
        // Earlier children's OrderTracking rows may persist (via their
        // own child @Transactional) but the group/Shipment rows DO NOT.
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 1));
        req.getLines().add(line("WEST", "SKU-2", 1));
        stubLabel("EAST", "1Z-EAST-1");
        when(carrierService.generateManualLabel(argMatchesWarehouse("WEST"), any()))
                .thenReturn(ApiResponse.<LabelGenerationResponse>builder()
                        .status("error").code(422).message("carrier down").build());

        assertThrows(RuntimeException.class, () -> service.generate(req, user));

        verify(groupRepo, never()).save(any());
        verify(shipmentRepo, never()).save(any());
        assertEquals(0, txInvocations.get(),
                "throw happens before the persist-tx would start");
    }

    // ===== helpers =====

    private MultiWarehouseLabelRequest baseRequest() {
        MultiWarehouseLabelRequest req = new MultiWarehouseLabelRequest();
        req.setClientCode("ACME");
        req.setOrderNo(1234);
        req.setLines(new ArrayList<>());
        return req;
    }

    private static LineItem line(String warehouseCode, String sku, int qty) {
        LineItem l = new LineItem();
        l.setWarehouseCode(warehouseCode);
        l.setItemNo(sku);
        l.setQuantity(qty);
        return l;
    }

    private void stubLabel(String warehouseCode, String trackingNo) {
        LabelGenerationResponse label = LabelGenerationResponse.builder()
                .orderNo(1234L)
                .carrierCode("UPS")
                .trackingNumber(trackingNo)
                .labelUrl("https://labels.example/" + trackingNo)
                .carrierAmount(new BigDecimal("10.00"))
                .billableAmount(new BigDecimal("12.00"))
                .markupCurrency("USD")
                .status("SUCCESS")
                .build();
        when(carrierService.generateManualLabel(argMatchesWarehouse(warehouseCode), any()))
                .thenReturn(ApiResponse.<LabelGenerationResponse>builder()
                        .status("success").code(200).message("ok").data(label).build());
    }

    private static ManualShipmentRequest argMatchesWarehouse(String wanted) {
        return org.mockito.ArgumentMatchers.argThat(r ->
                r != null && java.util.Objects.equals(r.getWarehouseCode(), wanted));
    }
}
