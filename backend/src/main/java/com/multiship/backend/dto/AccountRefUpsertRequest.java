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
public class AccountRefUpsertRequest {

    @NotBlank
    private String accountNumber;

    @NotBlank
    private String carrierCode;

    private String accountName;

    /**
     * OAuth client id. Optional on updates: blank means "keep the persisted
     * value". Required on create — the service throws VALIDATION_ERROR when
     * the underlying row is new and clientId is blank.
     */
    private String clientId;

    /** OAuth client secret. Same optional-on-update semantics as {@link #clientId}. */
    private String clientSecret;

    @Pattern(regexp = "(?i)SANDBOX|PRODUCTION", message = "Environment must be SANDBOX or PRODUCTION")
    private String environment;

    private String customerNo;

    /** Make this the linked client's default account (demotes the client's other accounts). */
    private Boolean clientDefault;
}
