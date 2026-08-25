package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.TrackingResponseDTO;
import com.multiship.backend.dto.TrackingResponseDTO.TrackingEventDTO;
import com.multiship.backend.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — TrackingController was 0-coverage per the test-
 * coverage audit. Public-facing tracking API — the wrong status returned
 * to a caller is directly customer-facing (e.g. "Delivered" when the
 * shipment is actually in-transit → customer thinks it's been stolen).
 *
 * <p>Focus: controller-owned status-echo. Carrier resolution, token
 * acquisition, cache TTLs (5 min in-flight / 24h delivered), and the
 * LIVE / STUB / CACHE source semantics are owned by TrackingServiceImpl
 * (TrackingServiceImplTest covers those).
 *
 * <p>Controller contract per the OpenAPI doc: response carries a
 * {@code source} flag (LIVE / STUB / CACHE). The FE renders a
 * freshness badge from this — swapping the enum values would break
 * the badge UI silently.
 */
class TrackingControllerTest {

    private TrackingService trackingService;
    private TrackingController controller;

    @BeforeEach
    void setUp() {
        trackingService = mock(TrackingService.class);
        controller = new TrackingController(trackingService);
    }

    // ─── LIVE source — happy path ──────────────────────────────────────────

    @Test
    void getLiveTracking_echoesServiceStatusCode_onLiveSuccess() {
        TrackingResponseDTO data = TrackingResponseDTO.builder()
                .trackingNumber("1Z999AA10123456784")
                .carrierCode("UPS")
                .status("IN_TRANSIT")
                .delivered(false)
                .currentLocation("Louisville, KY US")
                .estimatedDelivery(LocalDateTime.of(2026, 8, 21, 17, 0))
                .events(List.of(
                        TrackingEventDTO.builder()
                                .timestamp(LocalDateTime.of(2026, 8, 19, 10, 0))
                                .status("ORIGIN_SCAN")
                                .description("Origin scan")
                                .location("Atlanta, GA US")
                                .build(),
                        TrackingEventDTO.builder()
                                .timestamp(LocalDateTime.of(2026, 8, 19, 20, 30))
                                .status("IN_TRANSIT")
                                .description("Arrived at UPS facility")
                                .location("Louisville, KY US")
                                .build()))
                .source("LIVE")
                .build();
        ApiResponse<TrackingResponseDTO> serviceResp = ApiResponse.<TrackingResponseDTO>builder()
                .status("success").code(200).data(data).message("2 tracking events.")
                .build();
        when(trackingService.getLiveTracking(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<TrackingResponseDTO>> resp = controller.getLiveTracking(12345);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
        assertEquals("LIVE", resp.getBody().getData().getSource());
        assertEquals(2, resp.getBody().getData().getEvents().size());
        assertTrue(resp.getBody().getData().getEvents().get(0).getTimestamp()
                .isBefore(resp.getBody().getData().getEvents().get(1).getTimestamp()),
                "events should be oldest-first per the OpenAPI contract");
    }

    // ─── STUB source — no credentials configured ───────────────────────────

    @Test
    void getLiveTracking_echoesServiceStatusCode_onStubResponse() {
        // Regression guard: when no carrier credentials are configured
        // for the resolving client, the service returns 200 with
        // source=STUB + a URL-only response (no events). Controller must
        // NOT rewrite to a 4xx — the FE renders a "no live tracking
        // configured, follow carrier link" state from source=STUB.
        TrackingResponseDTO data = TrackingResponseDTO.builder()
                .trackingNumber("1Z999AA10123456784")
                .carrierCode("UPS")
                .status("UNKNOWN")
                .trackingUrl("https://www.ups.com/track?tracknum=1Z999AA10123456784")
                .source("STUB")
                .build();
        ApiResponse<TrackingResponseDTO> serviceResp = ApiResponse.<TrackingResponseDTO>builder()
                .status("success").code(200).data(data).message("STUB tracking — no credentials.")
                .build();
        when(trackingService.getLiveTracking(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<TrackingResponseDTO>> resp = controller.getLiveTracking(12345);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "controller must NOT surface STUB as 4xx — that would break the FE freshness-badge UI");
        assertEquals("STUB", resp.getBody().getData().getSource());
        // Events should be empty for STUB responses.
        assertTrue(resp.getBody().getData().getEvents() == null
                || resp.getBody().getData().getEvents().isEmpty());
    }

    // ─── CACHE source — cheap-cached path ──────────────────────────────────

    @Test
    void getLiveTracking_echoesServiceStatusCode_onCacheResponse() {
        // 5-min cache for in-flight / 24h for delivered per the OpenAPI
        // doc. Controller must preserve source=CACHE so the FE renders
        // the subtle "cached at HH:mm" hint instead of the "live" badge.
        TrackingResponseDTO data = TrackingResponseDTO.builder()
                .trackingNumber("1Z999AA10123456784")
                .carrierCode("UPS")
                .status("DELIVERED")
                .delivered(true)
                .source("CACHE")
                .build();
        ApiResponse<TrackingResponseDTO> serviceResp = ApiResponse.<TrackingResponseDTO>builder()
                .status("success").code(200).data(data).message("Cached tracking.")
                .build();
        when(trackingService.getLiveTracking(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<TrackingResponseDTO>> resp = controller.getLiveTracking(12345);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("CACHE", resp.getBody().getData().getSource());
        assertEquals(Boolean.TRUE, resp.getBody().getData().getDelivered());
    }

    // ─── error paths — controller must echo, not rewrite ─────────────────

    @Test
    void getLiveTracking_echoesServiceStatusCode_onOrderNotFound() {
        ApiResponse<TrackingResponseDTO> serviceResp = ApiResponse.<TrackingResponseDTO>builder()
                .status("error").code(404).errorCode("VALIDATION_ERROR")
                .message("Order 99999 was not found.")
                .build();
        when(trackingService.getLiveTracking(99999)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<TrackingResponseDTO>> resp = controller.getLiveTracking(99999);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
    }

    @Test
    void getLiveTracking_echoesServiceStatusCode_onNoTrackingNumber() {
        // Order exists but no label has been generated yet → no tracking
        // number to look up. Service returns 409 CONFLICT.
        ApiResponse<TrackingResponseDTO> serviceResp = ApiResponse.<TrackingResponseDTO>builder()
                .status("error").code(409).errorCode("NO_TRACKING_NUMBER")
                .message("Order has no generated label yet.")
                .build();
        when(trackingService.getLiveTracking(12345)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<TrackingResponseDTO>> resp = controller.getLiveTracking(12345);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("NO_TRACKING_NUMBER", resp.getBody().getErrorCode());
    }

    // ─── arg forwarding ────────────────────────────────────────────────────

    @Test
    void getLiveTracking_forwardsOrderNoToServiceAsInteger() {
        // Regression guard: the @PathVariable Integer arg is passed
        // through as-is. Prevents future accidental Long/String casts
        // that would silently miss orders whose id fits Integer but
        // was parsed differently upstream.
        when(trackingService.getLiveTracking(42)).thenReturn(
                ApiResponse.<TrackingResponseDTO>builder().status("success").code(200).build());

        controller.getLiveTracking(42);

        verify(trackingService).getLiveTracking(42);
    }
}
