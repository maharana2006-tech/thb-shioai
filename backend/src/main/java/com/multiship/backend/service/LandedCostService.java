package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LandedCostRequestDTO;
import com.multiship.backend.dto.LandedCostResponseDTO;

/**
 * Sprint 32 — estimate the total landed cost (freight + duties + taxes
 * + fees) for a cross-border shipment. Backs UPS Landed Cost, FedEx EDT,
 * and DHL Duties + Taxes. USPS is domestic-only and returns
 * NOT_SUPPORTED.
 */
public interface LandedCostService {

    ApiResponse<LandedCostResponseDTO> estimate(LandedCostRequestDTO request);
}
