package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.TrackingResponseDTO;

/**
 * Resolves live tracking for an order by looking up the shipment's carrier
 * account, getting a fresh access token, and calling the connector's 2-arg
 * {@code trackShipment(trackingNumber, accessToken)}. Sprints 12-15 wired
 * every connector's authenticated tracking path; Sprint 16 (this) hooks it
 * into a real endpoint the app can call.
 */
public interface TrackingService {

    /**
     * Look up the order's tracking number + carrier from
     * {@code order_label_tracking}, resolve the carrier account (falling
     * back to the platform account when the shipment's account isn't on the
     * carrier book), fetch an access token, and call the connector.
     *
     * <p>Results are cached in-memory for a short TTL to avoid hammering
     * carrier APIs on rapid UI refresh — a single package that hasn't
     * moved doesn't need a per-second track call. Delivered shipments are
     * cached longer since their status is terminal.
     */
    ApiResponse<TrackingResponseDTO> getLiveTracking(Integer orderNo);
}
