package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 31 — response body for {@code POST /api/v1/address/validate}.
 * Mirrors the connector {@link com.multiship.backend.service.carriers.CarrierConnector.AddressValidationResult}
 * record with a wire-friendlier suggested-address DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressValidationResponseDTO {

    private String carrierCode;
    private boolean valid;
    /** EXACT | CORRECTED | AMBIGUOUS | NOT_FOUND | NOT_SUPPORTED | ERROR. */
    private String matchLevel;
    /** RESIDENTIAL | COMMERCIAL | UNKNOWN. */
    private String classification;
    /** The carrier's suggested corrected address, if {@code matchLevel=CORRECTED}. */
    private SuggestedAddress suggested;
    private List<String> warnings;
    private String message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedAddress {
        private String name;
        private String addressLine1;
        private String addressLine2;
        private String addressLine3;
        private String city;
        private String state;
        private String postalCode;
        private String countryCode;
    }
}
