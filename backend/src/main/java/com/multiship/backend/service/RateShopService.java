package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;

/**
 * Sprint 21 fan-out orchestrator over the per-carrier {@code getRates}
 * overrides landed in Sprints 18-20. Given a shipment envelope, resolves
 * credentials for every configured carrier, calls their Rate API in
 * parallel, merges + sorts the results cheapest-first, and returns them
 * alongside a per-carrier status list so the UI can distinguish
 * "returned nothing" from "no credentials" from "call failed".
 */
public interface RateShopService {

    /**
     * Rate-shop the shipment across every carrier configured on this
     * instance (or the whitelist in {@code request.carriers} when set).
     * Never throws — carrier failures are captured per-carrier in the
     * response's {@code carrierResults} list.
     */
    ApiResponse<RateShopResponseDTO> rateShop(RateShopRequestDTO request);
}
