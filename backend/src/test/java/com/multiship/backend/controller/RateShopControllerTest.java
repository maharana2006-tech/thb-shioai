package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;
import com.multiship.backend.dto.RateShopResponseDTO.CarrierRateStatus;
import com.multiship.backend.dto.RateShopResponseDTO.RateOptionDTO;
import com.multiship.backend.service.RateShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — RateShopController was 0-coverage per the test-
 * coverage audit. Customer-facing pricing (customers see the quotes we
 * surface here) — a bad code path silently returning wrong rates would
 * either lose the sale or under-charge shipments for weeks before
 * anyone notices in the reconciliation.
 *
 * <p>Focus: controller-owned status-echo. The service ({@link
 * RateShopService}) owns the parallel-fan-out, per-carrier fallback,
 * timeout handling, and LIVE / STUB / ERROR source semantics — those
 * are covered separately by RateShopServiceImplTest.
 *
 * <p>Controller contract per the OpenAPI note: "Never throws — carrier
 * failures are surfaced in the response body." Pinning this contract
 * matters because a future controller change that added a `throw` on
 * empty options would break the FE's per-carrier status-badge UI.
 */
class RateShopControllerTest {

    private RateShopService rateShopService;
    private RateShopController controller;

    @BeforeEach
    void setUp() {
        rateShopService = mock(RateShopService.class);
        controller = new RateShopController(rateShopService);
    }

    // ─── happy path — merged sorted list + per-carrier statuses ────────────

    @Test
    void rateShop_echoesServiceStatusCode_onFullSuccess() {
        // 2 options across 2 carriers, cheapest-first (service is
        // responsible for the sort; controller must preserve).
        RateOptionDTO uspGround = RateOptionDTO.builder()
                .carrierCode("USPS").serviceCode("PRIORITY").serviceName("USPS Priority")
                .totalAmount(new BigDecimal("8.45")).currency("USD").transitDays(3)
                .build();
        RateOptionDTO upsGround = RateOptionDTO.builder()
                .carrierCode("UPS").serviceCode("03").serviceName("UPS Ground")
                .totalAmount(new BigDecimal("12.99")).currency("USD").transitDays(2)
                .build();
        List<CarrierRateStatus> statuses = List.of(
                CarrierRateStatus.builder().carrierCode("USPS").optionCount(1).source("LIVE")
                        .message("1 option from USPS").build(),
                CarrierRateStatus.builder().carrierCode("UPS").optionCount(1).source("LIVE")
                        .message("1 option from UPS").build());
        RateShopResponseDTO data = RateShopResponseDTO.builder()
                .options(List.of(uspGround, upsGround))
                .carrierResults(statuses)
                .build();
        ApiResponse<RateShopResponseDTO> serviceResp = ApiResponse.<RateShopResponseDTO>builder()
                .status("success").code(200).data(data).message("2 rates from 2 carriers.")
                .build();
        when(rateShopService.rateShop(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<RateShopResponseDTO>> resp =
                controller.rateShop(new RateShopRequestDTO());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
        assertEquals(2, resp.getBody().getData().getOptions().size());
        // First option should be USPS (cheaper) — order preserved from service.
        assertEquals("USPS", resp.getBody().getData().getOptions().get(0).getCarrierCode());
    }

    // ─── "never throws" contract — even when everything fails ─────────────

    @Test
    void rateShop_echoesServiceStatusCode_whenAllCarriersErrored() {
        // Regression guard: the endpoint contract explicitly says
        // "Never throws — carrier failures are surfaced in the response
        // body." If every carrier ERROR'd and returned zero options, the
        // service still hands back a 200 with an empty options list and
        // per-carrier ERROR statuses. The controller must NOT rewrite
        // this to a 502 / 500 — the FE renders the status list as
        // per-carrier badges.
        List<CarrierRateStatus> allErr = List.of(
                CarrierRateStatus.builder().carrierCode("UPS").optionCount(0).source("ERROR")
                        .message("UPS rate call failed: timeout").build(),
                CarrierRateStatus.builder().carrierCode("FEDEX").optionCount(0).source("ERROR")
                        .message("FedEx rate call failed: 500").build(),
                CarrierRateStatus.builder().carrierCode("USPS").optionCount(0).source("ERROR")
                        .message("USPS rate call failed: connection refused").build(),
                CarrierRateStatus.builder().carrierCode("DHL").optionCount(0).source("ERROR")
                        .message("DHL rate call failed: 401").build());
        RateShopResponseDTO data = RateShopResponseDTO.builder()
                .options(List.of())
                .carrierResults(allErr)
                .build();
        ApiResponse<RateShopResponseDTO> serviceResp = ApiResponse.<RateShopResponseDTO>builder()
                .status("success").code(200).data(data).message("0 rates — all 4 carriers failed.")
                .build();
        when(rateShopService.rateShop(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<RateShopResponseDTO>> resp =
                controller.rateShop(new RateShopRequestDTO());

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "controller must NOT surface all-ERROR as 5xx — that would break the FE badge UI");
        assertTrue(resp.getBody().getData().getOptions().isEmpty());
        assertEquals(4, resp.getBody().getData().getCarrierResults().size());
        assertTrue(resp.getBody().getData().getCarrierResults().stream()
                .allMatch(s -> "ERROR".equals(s.getSource())));
    }

    @Test
    void rateShop_echoesServiceStatusCode_onMixedLiveStubError() {
        // Mixed source list: LIVE (2 UPS options), STUB (FedEx has no
        // credentials configured), ERROR (DHL timed out). Controller
        // must preserve each carrier's source enum verbatim so the FE
        // renders the right badge — the "STUB" badge tells the operator
        // to configure credentials; the "ERROR" badge tells ops to
        // investigate.
        List<CarrierRateStatus> mixed = List.of(
                CarrierRateStatus.builder().carrierCode("UPS").optionCount(2).source("LIVE")
                        .message("2 options from UPS").build(),
                CarrierRateStatus.builder().carrierCode("FEDEX").optionCount(0).source("STUB")
                        .message("no credentials configured for FedEx").build(),
                CarrierRateStatus.builder().carrierCode("DHL").optionCount(0).source("ERROR")
                        .message("DHL rate call failed").build());
        RateShopResponseDTO data = RateShopResponseDTO.builder()
                .options(List.of(RateOptionDTO.builder().carrierCode("UPS").serviceCode("03").build(),
                                 RateOptionDTO.builder().carrierCode("UPS").serviceCode("02").build()))
                .carrierResults(mixed)
                .build();
        ApiResponse<RateShopResponseDTO> serviceResp = ApiResponse.<RateShopResponseDTO>builder()
                .status("success").code(200).data(data).build();
        when(rateShopService.rateShop(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<RateShopResponseDTO>> resp =
                controller.rateShop(new RateShopRequestDTO());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        // Preserve each source enum exactly.
        assertEquals("LIVE", resp.getBody().getData().getCarrierResults().get(0).getSource());
        assertEquals("STUB", resp.getBody().getData().getCarrierResults().get(1).getSource());
        assertEquals("ERROR", resp.getBody().getData().getCarrierResults().get(2).getSource());
    }

    // ─── validation failure ────────────────────────────────────────────────

    @Test
    void rateShop_echoesServiceStatusCode_onValidationFailure() {
        // @Valid on the DTO catches missing required fields at MVC
        // binding time (never reaches the controller). BUT the service
        // does its own cross-field validation (e.g. from + to address
        // must exist, weight > 0) and returns 400 for those.
        ApiResponse<RateShopResponseDTO> serviceResp = ApiResponse.<RateShopResponseDTO>builder()
                .status("error").code(400).errorCode("VALIDATION_ERROR")
                .message("weight must be greater than 0")
                .build();
        when(rateShopService.rateShop(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<RateShopResponseDTO>> resp =
                controller.rateShop(new RateShopRequestDTO());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
    }

    // ─── arg forwarding ────────────────────────────────────────────────────

    @Test
    void rateShop_forwardsRequestToServiceVerbatim() {
        RateShopRequestDTO req = new RateShopRequestDTO();
        when(rateShopService.rateShop(any()))
                .thenReturn(ApiResponse.<RateShopResponseDTO>builder().status("success").code(200).build());

        controller.rateShop(req);

        verify(rateShopService).rateShop(req);
    }
}
