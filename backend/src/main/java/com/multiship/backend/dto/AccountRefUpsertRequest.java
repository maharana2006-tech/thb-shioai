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

    /**
     * International-shipment defaults captured per account. Both optional —
     * carriers apply their own defaults when unset.
     *
     * shippingPurpose: SALE | GIFT | SAMPLE | REPAIR_AND_RETURN | DOCUMENTS |
     *   MERCHANDISE | PERSONAL_USE | RETURN
     * clearanceOption: per-carrier — UPS SENDER/RECEIVER/THIRD_PARTY,
     *   FEDEX SENDER/RECIPIENT/THIRD_PARTY, USPS DDU/DDP.
     *
     * Null on update = clear the persisted value (matches how customerNo /
     * accountName behave); omit the field entirely from the JSON body = keep
     * the persisted value untouched.
     */
    private String shippingPurpose;

    private String clearanceOption;

    /**
     * Third-party billing address — captured against the account when
     * clearanceOption = THIRD_PARTY. All optional here (backend validates only
     * the max lengths); the frontend enforces "at least account number"
     * before letting the operator save with clearance=THIRD_PARTY. Same
     * null-vs-empty-string semantics as {@link #shippingPurpose}: null =
     * keep persisted value, empty string = clear.
     */
    private String thirdPartyAccount;
    private String thirdPartyName;
    private String thirdPartyAddress1;
    private String thirdPartyCity;
    private String thirdPartyState;
    private String thirdPartyPostcode;
    private String thirdPartyCountry;
}
