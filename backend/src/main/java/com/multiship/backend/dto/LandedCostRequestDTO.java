package com.multiship.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 32 request body for {@code POST /api/v1/landed-cost}. Bundles
 * the shipment envelope with the target carrier (UPS | FEDEX | DHL) and
 * an optional customer number for credential resolution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandedCostRequestDTO {

    /** UPS | FEDEX | DHL. USPS returns NOT_SUPPORTED. Case-insensitive. */
    @NotBlank
    private String carrierCode;

    /** Optional — prefer the customer's own carrier account when set. */
    private String customerNo;

    @NotNull
    @Valid
    private ShipmentRequestDTO shipment;
}
