package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.TrackingResponseDTO;
import com.multiship.backend.service.TrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live tracking endpoint — wraps {@link TrackingService#getLiveTracking(Integer)}
 * so the app can display a real timeline instead of just a "click to open in
 * a new tab" link. The {@code /live} suffix keeps it distinct from any
 * pre-existing tracking URL/label endpoints on the OrderController.
 */
@Tag(name = "Live tracking", description = "Order tracking backed by the four connector APIs")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class TrackingController {

    private final TrackingService trackingService;

    @Operation(summary = "Live tracking for an order — status, events, estimated delivery",
            description = "Resolves the shipment's carrier account, fetches an access token, and calls " +
                    "the connector's authenticated tracking API. Response includes an oldest-first events " +
                    "list plus a source flag (LIVE / STUB / CACHE) so the UI can decide what freshness " +
                    "badge to show. Cached ~5 minutes for in-flight shipments, 24h for delivered.")
    // Sprint 50 Tier 0.5 PR E — scope live tracking to the order's tenant.
    // Controller SpEL is the fast reject; TrackingServiceImpl guards
    // belt-and-braces for any service-to-service caller.
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping("/{orderNo}/tracking/live")
    public ResponseEntity<ApiResponse<TrackingResponseDTO>> getLiveTracking(@PathVariable Integer orderNo) {
        ApiResponse<TrackingResponseDTO> response = trackingService.getLiveTracking(orderNo);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
