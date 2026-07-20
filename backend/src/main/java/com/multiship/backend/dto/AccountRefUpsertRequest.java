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

    @NotBlank
    private String clientId;

    @NotBlank
    private String clientSecret;

    @Pattern(regexp = "(?i)SANDBOX|PRODUCTION", message = "Environment must be SANDBOX or PRODUCTION")
    private String environment;

    private String customerNo;

    private Boolean setAsDefault;

    /** Make this the linked client's default account (demotes the client's other accounts). */
    private Boolean clientDefault;
}
