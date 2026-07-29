package com.multiship.backend.service;

import com.multiship.backend.dto.AddressValidationRequestDTO;
import com.multiship.backend.dto.AddressValidationResponseDTO;
import com.multiship.backend.dto.ApiResponse;

/**
 * Sprint 31 — validate a delivery address against a carrier's own
 * database. Resolves credentials the same way {@link TrackingService}
 * and {@link VoidService} do (customer first, platform fallback),
 * then calls {@link com.multiship.backend.service.carriers.CarrierConnector#validateAddress}
 * on the connector matching {@code carrierCode}.
 */
public interface AddressValidationService {

    ApiResponse<AddressValidationResponseDTO> validate(AddressValidationRequestDTO request);
}
