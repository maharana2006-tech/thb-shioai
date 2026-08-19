package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.service.ManifestService;
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
 * Coverage backfill — ManifestController was 0-coverage per the test-
 * coverage audit. End-of-day carrier close-out is high-consequence
 * (silent failures mean drivers can't accept the parcels next day),
 * so the controller's status-echo contract needs pinning.
 *
 * <p>Matches the pure-Mockito pattern from PickupControllerTest /
 * VoidControllerTest — no Spring context. Manifest carrier semantics,
 * tenant clamping, and DHL implicit-manifest handling are covered
 * separately by ManifestServiceImplTest.
 */
class ManifestControllerTest {

    private ManifestService manifestService;
    private ManifestController controller;

    @BeforeEach
    void setUp() {
        manifestService = mock(ManifestService.class);
        controller = new ManifestController(manifestService);
    }

    // ─── happy path ────────────────────────────────────────────────────────

    @Test
    void closeOut_echoesServiceStatusCode_onSuccess() {
        ManifestResponseDTO data = ManifestResponseDTO.builder()
                .carrierCode("UPS")
                .manifestId("1Z999AA10123456789M")
                .trackingCount(42)
                .status("MANIFESTED")
                .message("Manifest confirmed by UPS.")
                .build();
        ApiResponse<ManifestResponseDTO> serviceResp = ApiResponse.<ManifestResponseDTO>builder()
                .status("success").code(200).data(data).message("Close-out complete.")
                .build();
        when(manifestService.closeOut(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ManifestResponseDTO>> resp =
                controller.closeOut(new ManifestRequestDTO());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
        assertEquals("MANIFESTED", resp.getBody().getData().getStatus());
        assertEquals(42, resp.getBody().getData().getTrackingCount());
    }

    // ─── DHL implicit-manifest path — service returns NOT_SUPPORTED at 200 ─

    @Test
    void closeOut_echoesServiceStatusCode_onDhlNotSupported() {
        // Per the endpoint's OpenAPI note: "DHL manifests are implicit via
        // the pickup request (Sprint 33) — the response has status=NOT_SUPPORTED."
        // Controller must NOT rewrite this benign non-manifest to a 4xx/5xx
        // — the FE distinguishes NOT_SUPPORTED (informational) from
        // MANIFESTED (needs driver signature) purely on the status enum.
        ManifestResponseDTO data = ManifestResponseDTO.builder()
                .carrierCode("DHL")
                .status("NOT_SUPPORTED")
                .message("DHL manifests are implicit via pickup — no close-out required.")
                .build();
        ApiResponse<ManifestResponseDTO> serviceResp = ApiResponse.<ManifestResponseDTO>builder()
                .status("success").code(200).data(data).build();
        when(manifestService.closeOut(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ManifestResponseDTO>> resp =
                controller.closeOut(new ManifestRequestDTO());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("NOT_SUPPORTED", resp.getBody().getData().getStatus());
    }

    // ─── error paths — controller must NOT rewrite service's status ───────

    @Test
    void closeOut_echoesServiceStatusCode_onValidationFailure() {
        // Sprint 34 — the service returns 400 when the request has no
        // tracking numbers OR the ship-from address is incomplete
        // (@Valid on the DTO catches most, but service does its own
        // cross-field validation). Controller must echo as 400.
        ApiResponse<ManifestResponseDTO> serviceResp = ApiResponse.<ManifestResponseDTO>builder()
                .status("error").code(400).errorCode("VALIDATION_ERROR")
                .message("trackingNumbers must be non-empty.")
                .build();
        when(manifestService.closeOut(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ManifestResponseDTO>> resp =
                controller.closeOut(new ManifestRequestDTO());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
    }

    @Test
    void closeOut_echoesServiceStatusCode_onCarrierFailure() {
        // 502 CARRIER_FAILURE — UPS's End-of-Day endpoint had an outage.
        // Controller must not swallow / rewrite to 500; the caller
        // distinguishes 502 (retry later, carrier problem) from 500
        // (our bug).
        ApiResponse<ManifestResponseDTO> serviceResp = ApiResponse.<ManifestResponseDTO>builder()
                .status("error").code(502).errorCode("CARRIER_FAILURE")
                .message("UPS EOD endpoint returned 500 after 3 retries.")
                .build();
        when(manifestService.closeOut(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ManifestResponseDTO>> resp =
                controller.closeOut(new ManifestRequestDTO());

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("CARRIER_FAILURE", resp.getBody().getErrorCode());
    }

    @Test
    void closeOut_echoesServiceStatusCode_onTenantClamp() {
        // Sprint 50 Tier 0.5 PR E — the service's TenantScopeEnforcer
        // clamps request.customerNo to the caller's tenant. When a scoped
        // USER submits a customerNo that belongs to a foreign tenant, the
        // service returns 403 (per the class-level comment). Controller
        // must echo as-is — same as the BulkLabel tenant-mismatch guard.
        ApiResponse<ManifestResponseDTO> serviceResp = ApiResponse.<ManifestResponseDTO>builder()
                .status("error").code(403).errorCode("TENANT_MISMATCH")
                .message("customerNo=OTHER belongs to another tenant.")
                .build();
        when(manifestService.closeOut(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ManifestResponseDTO>> resp =
                controller.closeOut(new ManifestRequestDTO());

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("TENANT_MISMATCH", resp.getBody().getErrorCode());
    }

    // ─── arg forwarding ────────────────────────────────────────────────────

    @Test
    void closeOut_forwardsRequestToServiceVerbatim() {
        // Regression guard: the @Valid @RequestBody DTO must be passed
        // through as-is (no defensive copy that drops fields, no null
        // substitution). ManifestService owns any further mutation.
        ManifestRequestDTO req = new ManifestRequestDTO();
        when(manifestService.closeOut(any()))
                .thenReturn(ApiResponse.<ManifestResponseDTO>builder().status("success").code(200).build());

        controller.closeOut(req);

        verify(manifestService).closeOut(req);
    }
}
