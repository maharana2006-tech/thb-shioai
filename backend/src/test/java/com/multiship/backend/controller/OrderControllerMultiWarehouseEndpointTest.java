package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehouseLabelResponse;
import com.multiship.backend.service.external.IdempotencyService;
import com.multiship.backend.service.shipment.MultiWarehouseLabelService;
import com.multiship.backend.service.shipment.SplitAbortException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 47 PR2 — verifies the new /orders/multi-warehouse-label endpoint
 * delegates to the service and maps SplitAbortException to a 422 with the
 * offending warehouse in the message. Focused controller-level test —
 * everything the endpoint touches is mocked.
 */
class OrderControllerMultiWarehouseEndpointTest {

    private MultiWarehouseLabelService service;
    private OrderController controller;
    private UserDetails user;

    @BeforeEach
    void setUp() {
        service = mock(MultiWarehouseLabelService.class);
        controller = new OrderController();
        ReflectionTestUtils.setField(controller, "multiWarehouseLabelService", service);
        // Sprint 51 R2 — the controller now wraps the endpoint in
        // IdempotencyService.executeOrReplay. Mock it to always invoke
        // the supplier (that's the equivalent of Redis being absent or
        // Idempotency-Key omitted — the two happy no-op branches the
        // service already ships).
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.executeOrReplay(anyString(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.<Supplier<ResponseEntity<?>>>getArgument(3).get());
        // Also mock the (String, String, TypeReference, Supplier) 4-arg overload
        // when someone tests against it; harmless if unused.
        ReflectionTestUtils.setField(controller, "idempotency", idempotency);
        user = User.builder().username("alice").password("x").roles("USER").build();
    }

    @Test
    void delegatesToServiceAndPropagatesStatusCode() {
        MultiWarehouseLabelResponse body = MultiWarehouseLabelResponse.builder()
                .groupId(1L).shipmentCount(2).shipments(List.of()).build();
        when(service.generate(any(), any())).thenReturn(
                ApiResponse.<MultiWarehouseLabelResponse>builder()
                        .status("success").code(200).message("ok").data(body).build());

        ResponseEntity<ApiResponse<MultiWarehouseLabelResponse>> resp =
                controller.generateMultiWarehouseLabel(new MultiWarehouseLabelRequest(), user, null);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("success", resp.getBody().getStatus());
        assertEquals(1L, resp.getBody().getData().getGroupId());
        verify(service, times(1)).generate(any(), any());
    }

    @Test
    void splitAbortExceptionMapsTo422WithWarehouseInMessage() {
        // Service throws to trigger @Transactional rollback in production;
        // the controller must map that to a 422 with a specific message.
        when(service.generate(any(), any()))
                .thenThrow(new SplitAbortException(
                        "Warehouse WEST failed: carrier down",
                        "WEST", "carrier down"));

        ResponseEntity<ApiResponse<MultiWarehouseLabelResponse>> resp =
                controller.generateMultiWarehouseLabel(new MultiWarehouseLabelRequest(), user, null);

        assertEquals(422, resp.getStatusCode().value());
        assertEquals("error", resp.getBody().getStatus());
        assertEquals("CARRIER_FAILURE", resp.getBody().getErrorCode());
        String msg = resp.getBody().getMessage();
        assertTrue(msg.contains("WEST"), "message should name the offending warehouse: " + msg);
        assertTrue(msg.contains("carrier down"), "message should carry the underlying detail: " + msg);
        assertTrue(msg.contains("No labels were bought"),
                "message should reassure the operator that rollback happened: " + msg);
    }

    @Test
    void serviceValidationErrorReturnsItsOwnStatusCode() {
        // The service already emits its own error envelopes for validation
        // (400 on missing clientCode etc.). The controller should propagate
        // that code, not stamp its own.
        when(service.generate(any(), any())).thenReturn(
                ApiResponse.<MultiWarehouseLabelResponse>builder()
                        .status("error").code(400)
                        .message("clientCode is required").build());

        ResponseEntity<ApiResponse<MultiWarehouseLabelResponse>> resp =
                controller.generateMultiWarehouseLabel(new MultiWarehouseLabelRequest(), user, null);

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("error", resp.getBody().getStatus());
    }
}
