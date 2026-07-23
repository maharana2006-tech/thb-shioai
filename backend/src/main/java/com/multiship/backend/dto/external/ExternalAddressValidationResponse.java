package com.multiship.backend.dto.external;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Result of validating an address. */
@Data
@Builder
public class ExternalAddressValidationResponse {
    private boolean valid;
    private List<String> issues;
    private ExternalAddress normalized;
    /** True only when validated against a carrier address API (not just structurally). */
    private boolean carrierValidated;
}
