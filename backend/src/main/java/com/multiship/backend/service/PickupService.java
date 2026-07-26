package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.PickupRequestDTO;
import com.multiship.backend.dto.PickupResponseDTO;

/**
 * Sprint 33 — schedule a courier pickup with a carrier. Routes to UPS,
 * FedEx, DHL, or USPS/Stamps based on {@code carrierCode}. Never throws
 * — connector failures surface as {@code status=ERROR}.
 */
public interface PickupService {

    ApiResponse<PickupResponseDTO> schedule(PickupRequestDTO request);
}
