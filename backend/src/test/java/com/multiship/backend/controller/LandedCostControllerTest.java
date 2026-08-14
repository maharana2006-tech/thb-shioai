package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LandedCostRequestDTO;
import com.multiship.backend.dto.LandedCostResponseDTO;
import com.multiship.backend.service.LandedCostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for LandedCostController (was 0-coverage).
 * The controller is a one-liner that echoes the service ApiResponse code
 * as the HTTP status. Verify the echo does NOT rewrite (mirrors AC-M2
 * canonical error-code pattern).
 */
class LandedCostControllerTest {

    private LandedCostService landedCostService;
    private LandedCostController controller;

    @BeforeEach
    void setUp() {
        landedCostService = mock(LandedCostService.class);
        controller = new LandedCostController(landedCostService);
    }

    @Test
    void estimate_echoesServiceStatus_onSuccess200() {
        LandedCostResponseDTO data = LandedCostResponseDTO.builder()
                .carrierCode("UPS").source("LIVE").grandTotal(new BigDecimal("42.10"))
                .currency("USD").build();
        ApiResponse<LandedCostResponseDTO> serviceResp = ApiResponse.<LandedCostResponseDTO>builder()
                .status("SUCCESS").code(200).data(data).message("ok").build();
        when(landedCostService.estimate(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<LandedCostResponseDTO>> resp =
                controller.estimate(new LandedCostRequestDTO());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
        verify(landedCostService).estimate(any());
    }

    @Test
    void estimate_echoesServiceStatus_onCarrierFailure502() {
        ApiResponse<LandedCostResponseDTO> serviceResp = ApiResponse.<LandedCostResponseDTO>builder()
                .status("ERROR").code(502).message("UPS timeout")
                .errorCode(ErrorCode.CARRIER_FAILURE.name()).build();
        when(landedCostService.estimate(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<LandedCostResponseDTO>> resp =
                controller.estimate(new LandedCostRequestDTO());

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals(ErrorCode.CARRIER_FAILURE.name(), resp.getBody().getErrorCode());
    }

    @Test
    void estimate_echoesServiceStatus_onValidation400() {
        ApiResponse<LandedCostResponseDTO> serviceResp = ApiResponse.<LandedCostResponseDTO>builder()
                .status("ERROR").code(400).message("carrierCode is required")
                .errorCode(ErrorCode.VALIDATION_ERROR.name()).build();
        when(landedCostService.estimate(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<LandedCostResponseDTO>> resp =
                controller.estimate(new LandedCostRequestDTO());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
    }

    @Test
    void estimate_notSupported_returnsServiceCode422() {
        // USPS is domestic-only; service returns source=NOT_SUPPORTED with 422.
        LandedCostResponseDTO data = LandedCostResponseDTO.builder()
                .carrierCode("USPS").source("NOT_SUPPORTED").build();
        ApiResponse<LandedCostResponseDTO> serviceResp = ApiResponse.<LandedCostResponseDTO>builder()
                .status("ERROR").code(422).data(data).message("USPS unsupported")
                .errorCode(ErrorCode.VALIDATION_ERROR.name()).build();
        when(landedCostService.estimate(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<LandedCostResponseDTO>> resp =
                controller.estimate(new LandedCostRequestDTO());

        // Spring 6+ renamed the enum constant; value stays 422.
        assertEquals(422, resp.getStatusCode().value());
        assertEquals("NOT_SUPPORTED", resp.getBody().getData().getSource());
    }
}
