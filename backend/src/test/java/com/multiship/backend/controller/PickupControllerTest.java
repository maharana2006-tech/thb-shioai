package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.PickupRequestDTO;
import com.multiship.backend.dto.PickupResponseDTO;
import com.multiship.backend.service.PickupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for PickupController (was 0-coverage per
 * the audit). Focused on the code that lives IN the controller (status
 * echo, delegation) — pickup semantics are covered separately by
 * PickupServiceImplTest.
 */
class PickupControllerTest {

    private PickupService pickupService;
    private PickupController controller;

    @BeforeEach
    void setUp() {
        pickupService = mock(PickupService.class);
        controller = new PickupController(pickupService);
    }

    @Test
    void schedule_echoesServiceStatusCode_onSuccess() {
        PickupResponseDTO data = new PickupResponseDTO();
        ApiResponse<PickupResponseDTO> serviceResp = ApiResponse.<PickupResponseDTO>builder()
                .status("success").code(200).data(data).message("scheduled").build();
        when(pickupService.schedule(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<PickupResponseDTO>> resp = controller.schedule(new PickupRequestDTO());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
        verify(pickupService).schedule(any());
    }

    @Test
    void schedule_echoesServiceStatusCode_onCarrierFailure() {
        // Sprint 51 AC-M6 — verify the controller does NOT rewrite a
        // service-returned non-200 to 200; the audit-fix pattern in v2 is
        // ResponseEntity.status(result.getCode()).body(result).
        ApiResponse<PickupResponseDTO> serviceResp = ApiResponse.<PickupResponseDTO>builder()
                .status("error").code(502).message("UPS timeout").errorCode("CARRIER_FAILURE").build();
        when(pickupService.schedule(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<PickupResponseDTO>> resp = controller.schedule(new PickupRequestDTO());

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("CARRIER_FAILURE", resp.getBody().getErrorCode());
    }

    @Test
    void schedule_echoesServiceStatusCode_onValidationFailure() {
        ApiResponse<PickupResponseDTO> serviceResp = ApiResponse.<PickupResponseDTO>builder()
                .status("error").code(400).message("carrierCode is required")
                .errorCode("VALIDATION_ERROR").build();
        when(pickupService.schedule(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<PickupResponseDTO>> resp = controller.schedule(new PickupRequestDTO());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
    }
}
