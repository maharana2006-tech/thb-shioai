package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.VoidLabelResponseDTO;

/**
 * Sprint 30 — void / cancel a previously-issued label. Given an order
 * number, resolves the carrier account for the tracking number, calls
 * the connector's authenticated {@code voidShipment}, and marks the
 * order's label state as VOIDED in {@code order_label_tracking}.
 *
 * <p>Idempotent: voiding an already-VOIDED order is a fast success
 * without a carrier round-trip.
 */
public interface VoidService {

    /** Void the label for {@code orderNo}. */
    ApiResponse<VoidLabelResponseDTO> voidLabel(Integer orderNo);
}
