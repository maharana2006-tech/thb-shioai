package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierConnectRequest {

    @NotBlank
    private String carrierCode;

    @NotBlank
    private String clientId;

    @NotBlank
    private String clientSecret;

    @NotBlank
    private String accountNumber;

    @Pattern(regexp = "(?i)SANDBOX|PRODUCTION", message = "Environment must be SANDBOX or PRODUCTION")
    private String environment;

    /**
     * Optional tenant (customer) code this carrier account belongs to.
     * When present, the connection is stored as a tenant carrier account and
     * becomes eligible for automatic selection during label generation.
     */
    private String tenantId;

    /** When true (and tenantId is present), this account becomes the tenant's default. */
    private Boolean setAsDefault;
}
