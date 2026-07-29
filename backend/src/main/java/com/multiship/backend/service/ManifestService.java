package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;

/**
 * Sprint 34 — close out the day's shipments at a carrier and return the
 * manifest identifier + optional PDF. Routes to UPS End of Day, FedEx
 * CloseShipment, or SWSIM CreateScanForm based on {@code carrierCode}.
 * DHL is NOT_SUPPORTED (its manifests are implicit via pickup).
 */
public interface ManifestService {

    ApiResponse<ManifestResponseDTO> closeOut(ManifestRequestDTO request);
}
