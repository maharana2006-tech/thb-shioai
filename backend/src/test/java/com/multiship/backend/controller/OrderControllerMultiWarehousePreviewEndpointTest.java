package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehousePreviewResponse;
import com.multiship.backend.service.shipment.MultiWarehousePreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 47 PR4 — verifies the /orders/multi-warehouse-preview endpoint
 * delegates to the service and propagates its status code (200 on happy,
 * 400 on validation failures the service caught).
 */
class OrderControllerMultiWarehousePreviewEndpointTest {

    private MultiWarehousePreviewService service;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        service = mock(MultiWarehousePreviewService.class);
        controller = new OrderController();
        ReflectionTestUtils.setField(controller, "multiWarehousePreviewService", service);
    }

    @Test
    void delegatesAndPropagatesSuccess() {
        MultiWarehousePreviewResponse body = MultiWarehousePreviewResponse.builder()
                .clientCode("ACME").totalLines(3).shipmentCount(2)
                .unassignedLineCount(0).groups(List.of()).lines(List.of()).build();
        when(service.preview(any())).thenReturn(
                ApiResponse.<MultiWarehousePreviewResponse>builder()
                        .status("success").code(200).message("ok").data(body).build());

        ResponseEntity<ApiResponse<MultiWarehousePreviewResponse>> resp =
                controller.previewMultiWarehouseSplit(new MultiWarehouseLabelRequest());

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("ACME", resp.getBody().getData().getClientCode());
        assertEquals(2, resp.getBody().getData().getShipmentCount());
        verify(service, times(1)).preview(any());
    }

    @Test
    void propagatesValidationErrorStatusCode() {
        when(service.preview(any())).thenReturn(
                ApiResponse.<MultiWarehousePreviewResponse>builder()
                        .status("error").code(400)
                        .errorCode("VALIDATION_ERROR")
                        .message("clientCode is required.").build());

        ResponseEntity<ApiResponse<MultiWarehousePreviewResponse>> resp =
                controller.previewMultiWarehouseSplit(new MultiWarehouseLabelRequest());

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("error", resp.getBody().getStatus());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
    }
}
