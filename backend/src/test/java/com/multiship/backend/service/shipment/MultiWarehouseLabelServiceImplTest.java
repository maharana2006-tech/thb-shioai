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
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * Sprint 47 PR1 — split-shipment service. Covers happy path, degenerate
 * single-warehouse case, validation rejections, and fail-all rollback.
 */
class MultiWarehouseLabelServiceImplTest {

    private CarrierService carrierService;
    private ShipmentGroupRepository groupRepo;
    private ShipmentRepository shipmentRepo;
    private MultiWarehouseLabelServiceImpl service;
    private UserDetails user;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        groupRepo = mock(ShipmentGroupRepository.class);
        shipmentRepo = mock(ShipmentRepository.class);
        user = mock(UserDetails.class);
        when(user.getUsername()).thenReturn("alice");
        // Repos echo the entity back with a synthetic id.
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
        // Sprint 50 Tier 0.5 PR E - enforcer with flag OFF is a pure
        // pass-through, so existing test behavior is unchanged.
        service = new MultiWarehouseLabelServiceImpl(carrierService, groupRepo, shipmentRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    // ===== Happy path =====

    @Test
    void splitsAcrossTwoWarehousesAndPersistsGroupPlusChildren() {
        // Two lines in EAST, one in WEST → two child labels.
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 2));
        req.getLines().add(line("EAST", "SKU-2", 1));
        req.getLines().add(line("WEST", "SKU-3", 5));

        stubLabel("EAST", "1Z-EAST-42");
        stubLabel("WEST", "1Z-WEST-43");

        ApiResponse<MultiWarehouseLabelResponse> resp = service.generate(req, user);

        assertEquals("success", resp.getStatus());
        MultiWarehouseLabelResponse body = resp.getData();
        assertEquals(2, body.getShipmentCount());
        assertEquals(2, body.getShipments().size());
        // Order preserved: EAST first (first-seen), WEST second.
        assertEquals("EAST", body.getShipments().get(0).getWarehouseCode());
        assertEquals("1Z-EAST-42", body.getShipments().get(0).getTrackingNumber());
        assertEquals(2, body.getShipments().get(0).getLineCount(), "EAST got 2 lines");
        assertEquals("WEST", body.getShipments().get(1).getWarehouseCode());
        assertEquals(1, body.getShipments().get(1).getLineCount());

        // Group persisted with shipmentCount denormalised.
        ArgumentCaptor<ShipmentGroup> gCap = ArgumentCaptor.forClass(ShipmentGroup.class);
        verify(groupRepo).save(gCap.capture());
        assertEquals(2, gCap.getValue().getShipmentCount());
        assertEquals("alice", gCap.getValue().getCreatedBy());
        assertEquals("ACME", gCap.getValue().getClientCode());

        // Two shipments persisted, both stamped with the group id.
        ArgumentCaptor<Shipment> sCap = ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentRepo, times(2)).save(sCap.capture());
        for (Shipment s : sCap.getAllValues()) {
            assertEquals(999L, s.getGroupId());
            assertEquals("ACME", s.getClientCode());
            assertEquals("CREATED", s.getStatus());
        }
    }

    @Test
    void singleWarehouseStillReturnsGroupResponseShape() {
        // All lines in one warehouse — service still runs, group + 1 shipment.
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 1));
        req.getLines().add(line("EAST", "SKU-2", 1));
        stubLabel("EAST", "1Z-EAST-99");

        ApiResponse<MultiWarehouseLabelResponse> resp = service.generate(req, user);

        assertEquals("success", resp.getStatus());
        assertEquals(1, resp.getData().getShipmentCount());
        assertEquals(1, resp.getData().getShipments().size());
        verify(carrierService, times(1)).generateManualLabel(any(), any());
    }

    @Test
    void lineWeightsSumIntoChildRequest() {
        // Two lines, weights 2 (qty 3) + 1.5 (qty 2) → total 9 for the EAST child.
        MultiWarehouseLabelRequest req = baseRequest();
        LineItem a = line("EAST", "SKU-1", 3); a.setWeight(new BigDecimal("2"));
        LineItem b = line("EAST", "SKU-2", 2); b.setWeight(new BigDecimal("1.5"));
        req.getLines().add(a); req.getLines().add(b);
        stubLabel("EAST", "1Z-EAST-77");

        ArgumentCaptor<ManualShipmentRequest> childCap =
                ArgumentCaptor.forClass(ManualShipmentRequest.class);
        service.generate(req, user);
        verify(carrierService).generateManualLabel(childCap.capture(), any());
        assertEquals(0, new BigDecimal("9.0").compareTo(childCap.getValue().getWeight()),
                "child weight = sum(line.weight * qty)");
    }

    // ===== Validation =====

    @Test
    void rejectsNullRequest() {
        ApiResponse<MultiWarehouseLabelResponse> resp = service.generate(null, user);
        assertEquals("error", resp.getStatus());
        assertEquals(400, resp.getCode());
    }

    @Test
    void rejectsMissingClientCode() {
        MultiWarehouseLabelRequest req = baseRequest();
        req.setClientCode(null);
        req.getLines().add(line("EAST", "SKU-1", 1));
        ApiResponse<MultiWarehouseLabelResponse> resp = service.generate(req, user);
        assertEquals("error", resp.getStatus());
        assertTrue(resp.getMessage().toLowerCase().contains("clientcode"),
                resp.getMessage());
    }

    @Test
    void rejectsEmptyLines() {
        ApiResponse<MultiWarehouseLabelResponse> resp = service.generate(baseRequest(), user);
        assertEquals("error", resp.getStatus());
    }

    @Test
    void rejectsLineMissingWarehouseCode() {
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 1));
        req.getLines().add(line(null, "SKU-2", 1)); // <- offender
        ApiResponse<MultiWarehouseLabelResponse> resp = service.generate(req, user);
        assertEquals("error", resp.getStatus());
        assertTrue(resp.getMessage().contains("Line 2"),
                "Message should name the offending line index: " + resp.getMessage());
    }

    // ===== Fail-all rollback =====

    @Test
    void anyChildFailure_throwsToTriggerTransactionalRollback() {
        // EAST succeeds; WEST fails. Service must throw so the outer
        // @Transactional rolls back — persisting nothing.
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 1));
        req.getLines().add(line("WEST", "SKU-2", 1));
        stubLabel("EAST", "1Z-EAST-1");
        // WEST returns an error envelope.
        when(carrierService.generateManualLabel(argMatchesWarehouse("WEST"), any()))
                .thenReturn(ApiResponse.<LabelGenerationResponse>builder()
                        .status("error").code(422)
                        .message("carrier down")
                        .build());

        assertThrows(RuntimeException.class,
                () -> service.generate(req, user),
                "Any child error must abort the whole batch");

        // Group / shipments were never persisted (assertion-level: since we
        // throw before groupRepo.save, no group save was made).
        verify(groupRepo, never()).save(any());
        verify(shipmentRepo, never()).save(any());
    }

    @Test
    void carrierServiceThrows_alsoAborts() {
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("EAST", "SKU-1", 1));
        when(carrierService.generateManualLabel(any(), any()))
                .thenThrow(new RuntimeException("boom"));
        assertThrows(RuntimeException.class, () -> service.generate(req, user));
        verify(groupRepo, never()).save(any());
    }

    /* -------- Sprint 50 Tier 0.5 PR E: tenant-scope -------- */

    @Test
    void scopedUserCannotGenerateForForeignTenant() {
        // Arrange: put a scoped USER (ACME) in the security context.
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        try {
            MultiWarehouseLabelServiceImpl scopedService = new MultiWarehouseLabelServiceImpl(
                    carrierService, groupRepo, shipmentRepo,
                    new TenantScopeEnforcer(new AccessScopePolicy(true)));

            MultiWarehouseLabelRequest req = new MultiWarehouseLabelRequest();
            req.setClientCode("OTHER");
            req.setOrderNo(1);
            req.setLines(new ArrayList<>());
            req.getLines().add(line("EAST", "SKU-1", 1));

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> scopedService.generate(req, user));
            verify(groupRepo, never()).save(any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
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

    /** Argument matcher: a ManualShipmentRequest whose warehouseCode equals wanted. */
    private static ManualShipmentRequest argMatchesWarehouse(String wanted) {
        return org.mockito.ArgumentMatchers.argThat(r ->
                r != null && java.util.Objects.equals(r.getWarehouseCode(), wanted));
    }
}
