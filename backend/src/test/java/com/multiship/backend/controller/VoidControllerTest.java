package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.service.VoidService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — VoidController was 0-coverage per the test-coverage
 * audit. Focused on the controller-layer contract (status-echo, arg
 * forwarding). Carrier-void semantics, tenant enforcement, and idempotency
 * are covered separately by VoidServiceImplTest / carrier-connector suites.
 *
 * <p>Matches the pure-Mockito pattern from {@link PickupControllerTest} —
 * no Spring context, no @PreAuthorize evaluation (SpEL requires Spring
 * Security wiring; that's an integration-test concern).
 */
class VoidControllerTest {

    private VoidService voidService;
    private VoidController controller;

    @BeforeEach
    void setUp() {
        voidService = mock(VoidService.class);
        controller = new VoidController(voidService);
    }

    // ─── happy path ────────────────────────────────────────────────────────

    @Test
    void voidLabel_echoesServiceStatusCode_onSuccess() {
        VoidLabelResponseDTO data = VoidLabelResponseDTO.builder()
                .orderNo(12345).trackingNumber("1Z999AA10123456784").carrierCode("UPS")
                .voided(true).status("VOIDED").message("Void confirmed by UPS.")
                .build();
        ApiResponse<VoidLabelResponseDTO> serviceResp = ApiResponse.<VoidLabelResponseDTO>builder()
                .status("success").code(200).data(data).message("Label voided.").build();
        when(voidService.voidLabel(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<VoidLabelResponseDTO>> resp = controller.voidLabel(12345);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
        assertTrue(resp.getBody().getData().isVoided());
        assertEquals("VOIDED", resp.getBody().getData().getStatus());
    }

    // ─── idempotent already-voided ─────────────────────────────────────────

    @Test
    void voidLabel_echoesServiceStatusCode_onAlreadyVoided() {
        // Per the OpenAPI note on the endpoint: "voiding an already-VOIDED
        // order returns 200 without a carrier round-trip." The controller
        // must not rewrite the 200 code that the service chose for this
        // idempotent branch.
        VoidLabelResponseDTO data = VoidLabelResponseDTO.builder()
                .orderNo(12345).status("ALREADY_VOIDED").voided(true)
                .message("Already voided at 2026-08-19T10:15:00Z.")
                .build();
        ApiResponse<VoidLabelResponseDTO> serviceResp = ApiResponse.<VoidLabelResponseDTO>builder()
                .status("success").code(200).data(data).build();
        when(voidService.voidLabel(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<VoidLabelResponseDTO>> resp = controller.voidLabel(12345);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("ALREADY_VOIDED", resp.getBody().getData().getStatus());
    }

    // ─── error paths — controller must NOT rewrite service's status ───────

    @Test
    void voidLabel_echoesServiceStatusCode_onOrderNotFound() {
        ApiResponse<VoidLabelResponseDTO> serviceResp = ApiResponse.<VoidLabelResponseDTO>builder()
                .status("error").code(404).errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message("Order 99999 was not found.")
                .build();
        when(voidService.voidLabel(99999)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<VoidLabelResponseDTO>> resp = controller.voidLabel(99999);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
    }

    @Test
    void voidLabel_echoesServiceStatusCode_onCarrierFailure() {
        // 502 CARRIER_FAILURE — service surfaces the upstream carrier's
        // failure; controller must not swallow / rewrite to 500.
        ApiResponse<VoidLabelResponseDTO> serviceResp = ApiResponse.<VoidLabelResponseDTO>builder()
                .status("error").code(502).errorCode("CARRIER_FAILURE")
                .message("UPS: void endpoint returned 500.")
                .build();
        when(voidService.voidLabel(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<VoidLabelResponseDTO>> resp = controller.voidLabel(12345);

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("CARRIER_FAILURE", resp.getBody().getErrorCode());
    }

    @Test
    void voidLabel_echoesServiceStatusCode_onLabelNotYetGenerated() {
        // Voiding an order that never had a label at all — service returns
        // 409 CONFLICT; controller must echo, not remap.
        ApiResponse<VoidLabelResponseDTO> serviceResp = ApiResponse.<VoidLabelResponseDTO>builder()
                .status("error").code(409).errorCode("NO_LABEL_TO_VOID")
                .message("Order has no generated label.")
                .build();
        when(voidService.voidLabel(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<VoidLabelResponseDTO>> resp = controller.voidLabel(12345);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("NO_LABEL_TO_VOID", resp.getBody().getErrorCode());
    }

    // ─── arg forwarding ────────────────────────────────────────────────────

    @Test
    void voidLabel_forwardsOrderNoToService() {
        // Regression guard: ensure the @PathVariable Integer arg is
        // passed through as-is (no accidental Integer.parseInt off the
        // hot path, no cast-to-Long).
        when(voidService.voidLabel(42)).thenReturn(
                ApiResponse.<VoidLabelResponseDTO>builder().status("success").code(200).build());

        controller.voidLabel(42);

        verify(voidService).voidLabel(42);
    }
}
