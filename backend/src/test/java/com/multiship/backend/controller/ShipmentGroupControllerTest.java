package com.multiship.backend.controller;

import com.multiship.backend.config.OrderAccessEvaluator;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ShipmentGroupDetailDTO;
import com.multiship.backend.dto.ShipmentGroupSummaryDTO;
import com.multiship.backend.model.Shipment;
import com.multiship.backend.model.ShipmentGroup;
import com.multiship.backend.repository.ShipmentGroupRepository;
import com.multiship.backend.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 47 PR3 — read endpoints for the split-shipment groups. Focused
 * controller-level test: repos + OrderAccessEvaluator mocked.
 */
class ShipmentGroupControllerTest {

    private ShipmentGroupRepository groupRepo;
    private ShipmentRepository shipmentRepo;
    private OrderAccessEvaluator orderAccess;
    private ShipmentGroupController controller;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        groupRepo = mock(ShipmentGroupRepository.class);
        shipmentRepo = mock(ShipmentRepository.class);
        orderAccess = mock(OrderAccessEvaluator.class);
        controller = new ShipmentGroupController(groupRepo, shipmentRepo, orderAccess);
        auth = mock(Authentication.class);
    }

    // ===== getById =====

    @Test
    void getById_returnsGroupWithChildrenWhenAuthorised() {
        ShipmentGroup group = ShipmentGroup.builder()
                .id(1L).clientCode("ACME").orderNo(1234).shipmentCount(2)
                .createdBy("alice").createdAt(LocalDateTime.now()).build();
        when(groupRepo.findById(1L)).thenReturn(Optional.of(group));
        when(orderAccess.canViewTenant(auth, "ACME")).thenReturn(true);
        when(shipmentRepo.findByGroupIdOrderByIdAsc(1L)).thenReturn(List.of(
                Shipment.builder().id(10L).warehouseCode("EAST").status("CREATED").build(),
                Shipment.builder().id(11L).warehouseCode("WEST").status("CREATED").build()));

        ResponseEntity<ApiResponse<ShipmentGroupDetailDTO>> resp =
                controller.getById(1L, auth);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("success", resp.getBody().getStatus());
        ShipmentGroupDetailDTO body = resp.getBody().getData();
        assertEquals(1L, body.getId());
        assertEquals("ACME", body.getClientCode());
        assertEquals(2, body.getShipments().size());
        assertEquals("EAST", body.getShipments().get(0).getWarehouseCode());
    }

    @Test
    void getById_returns404WhenMissing() {
        when(groupRepo.findById(42L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<ShipmentGroupDetailDTO>> resp =
                controller.getById(42L, auth);

        assertEquals(404, resp.getStatusCode().value());
        assertEquals("SHIPMENT_GROUP_NOT_FOUND", resp.getBody().getErrorCode());
        // Auth check never fired because there was nothing to authorise.
        verifyNoInteractions(orderAccess);
        // No child lookup either.
        verifyNoInteractions(shipmentRepo);
    }

    @Test
    void getById_returns403WhenTenantMismatch() {
        // Group belongs to ACME but the tenant caller is EMEA — reject.
        ShipmentGroup group = ShipmentGroup.builder()
                .id(1L).clientCode("ACME").build();
        when(groupRepo.findById(1L)).thenReturn(Optional.of(group));
        when(orderAccess.canViewTenant(auth, "ACME")).thenReturn(false);

        ResponseEntity<ApiResponse<ShipmentGroupDetailDTO>> resp =
                controller.getById(1L, auth);

        assertEquals(403, resp.getStatusCode().value());
        assertEquals("FORBIDDEN", resp.getBody().getErrorCode());
        assertNull(resp.getBody().getData());
        // We never fetched the children — no leak of the group's shipments.
        verifyNoInteractions(shipmentRepo);
    }

    // ===== list =====

    @Test
    void list_byClientCode_returnsSummaryList() {
        when(groupRepo.findByClientCodeIgnoreCaseOrderByCreatedAtDesc("ACME")).thenReturn(List.of(
                ShipmentGroup.builder().id(1L).clientCode("ACME").shipmentCount(2).build(),
                ShipmentGroup.builder().id(2L).clientCode("ACME").shipmentCount(1).build()));

        ResponseEntity<ApiResponse<List<ShipmentGroupSummaryDTO>>> resp =
                controller.list("ACME", null);

        assertEquals(200, resp.getStatusCode().value());
        List<ShipmentGroupSummaryDTO> body = resp.getBody().getData();
        assertEquals(2, body.size());
        // Neither summary carries the shipments list — that's the point of the summary.
        // (Summary DTO doesn't even have a shipments field, so nothing to assert.)
        verify(groupRepo).findByClientCodeIgnoreCaseOrderByCreatedAtDesc("ACME");
    }

    @Test
    void list_byOrderNo_returnsSummaryList() {
        when(groupRepo.findByOrderNo(1234)).thenReturn(List.of(
                ShipmentGroup.builder().id(7L).clientCode("ACME").orderNo(1234).build()));

        ResponseEntity<ApiResponse<List<ShipmentGroupSummaryDTO>>> resp =
                controller.list(null, 1234);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, resp.getBody().getData().size());
        assertEquals(1234, resp.getBody().getData().get(0).getOrderNo());
    }

    @Test
    void list_rejectsWhenNeitherParamSupplied() {
        ResponseEntity<ApiResponse<List<ShipmentGroupSummaryDTO>>> resp =
                controller.list(null, null);

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
        verifyNoInteractions(groupRepo);
    }

    @Test
    void list_rejectsWhenBothParamsSupplied() {
        // Ambiguous — force the caller to pick one.
        ResponseEntity<ApiResponse<List<ShipmentGroupSummaryDTO>>> resp =
                controller.list("ACME", 1234);

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
        verifyNoInteractions(groupRepo);
    }

    @Test
    void list_trimsClientCode() {
        // Trailing whitespace is common in copy-pasted codes; make sure the
        // repo query sees the trimmed value so it hits the index.
        when(groupRepo.findByClientCodeIgnoreCaseOrderByCreatedAtDesc(eq("ACME")))
                .thenReturn(List.of());

        controller.list("  ACME  ", null);

        verify(groupRepo).findByClientCodeIgnoreCaseOrderByCreatedAtDesc("ACME");
    }
}
