package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 31 — request body for {@code POST /api/v1/address/validate}.
 * The caller supplies the address to validate + which carrier's API to
 * hit (UPS AVS, FedEx AV, DHL address-validate, SWSIM CleanseAddress).
 * Credentials resolve on the backend from the customer's carrier
 * accounts (fall back to platform).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressValidationRequestDTO {

    /** UPS | FEDEX | USPS | DHL. Case-insensitive. */
    @NotBlank
    private String carrierCode;

    /** Optional — prefer the customer's own carrier account when set. */
    private String customerNo;

    private String name;
    private String company;

    @NotBlank
    private String addressLine1;

    private String addressLine2;
    private String addressLine3;

    @NotBlank
    private String city;

    private String state;

    @NotBlank
    private String postalCode;

    @NotBlank
    private String countryCode;
}
