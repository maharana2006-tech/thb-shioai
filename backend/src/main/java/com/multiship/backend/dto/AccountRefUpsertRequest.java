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
     * F6-B2 — per-account billing currency (ISO 4217, e.g. USD / EUR / GBP).
     * Nullable; NULL means "use the carrier's hardcoded home currency"
     * (USPS/UPS/FedEx → USD, DHL → EUR). Non-NULL overrides both the
     * carrier default AND the client's default currency. If it differs
     * from the resolved client currency, F6-D converts money-shaped
     * request fields via FxRateService before sending to the carrier.
     * Same null-vs-empty-string semantics as {@link #shippingPurpose}:
     * null = keep persisted value, empty string = clear.
     */
    @jakarta.validation.constraints.Size(min = 3, max = 3,
            message = "currency must be a 3-letter ISO 4217 code (e.g. USD, EUR, GBP)")
    @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z]{3}|",
            message = "currency must be alphabetic ISO 4217")
    private String currency;

    /**
     * FDX-H1 — per-account default pickupType. Only FedEx maps this to its
     * shipment envelope (UPS / DHL / SWSIM ignore). Constrained to the FedEx
     * pickupType enum values via regex; empty string clears the persisted
     * value, null keeps it.
     *
     * <p>Values (FedEx REGULAR_PICKUP / REQUEST_COURIER / DROP_BOX /
     * BUSINESS_SERVICE_CENTER / STATION / USE_SCHEDULED_PICKUP). The
     * CONTACT_FEDEX_TO_SCHEDULE value is applied automatically by the
     * FedEx connector for return labels and never set on this column.
     *
     * <p>NULL / unset → resolver falls back to USE_SCHEDULED_PICKUP
     * (matches the pre-FDX-H1 hardcode). Non-NULL → the account's
     * chosen default is baked into every non-return shipment.
     */
    @jakarta.validation.constraints.Pattern(
            regexp = "REGULAR_PICKUP|REQUEST_COURIER|DROP_BOX|BUSINESS_SERVICE_CENTER|STATION|USE_SCHEDULED_PICKUP|",
            message = "pickupType must be one of REGULAR_PICKUP / REQUEST_COURIER / DROP_BOX / "
                    + "BUSINESS_SERVICE_CENTER / STATION / USE_SCHEDULED_PICKUP")
    private String pickupType;

    /**
     * UPS-4a — per-account UPS LabelImageFormat. Only UPS maps this to the
     * shipment envelope (FedEx / DHL / SWSIM ignore). Constrained to the UPS
     * LabelImageFormat enum values via regex; empty string clears the
     * persisted value, null keeps it.
     *
     * <p>Values: GIF | PDF | PNG | ZPL | EPL. NULL / unset → resolver
     * falls back to {@code GIF} (matches the pre-UPS-4a hardcode).
     */
    @jakarta.validation.constraints.Pattern(
            regexp = "GIF|PDF|PNG|ZPL|EPL|",
            message = "labelImageFormat must be one of GIF / PDF / PNG / ZPL / EPL")
    private String labelImageFormat;

    /**
     * FDX-H3 — per-account FedEx labelSpecification.imageType. Only FedEx
     * maps this to the shipment envelope (UPS / DHL / SWSIM ignore).
     * Constrained to the FedEx imageType enum values via regex; empty
     * string clears the persisted value, null keeps it.
     *
     * <p>Values: PDF | PNG | ZPLII | EPL2 | DPL. NULL / unset → resolver
     * falls back to {@code PDF} (matches the pre-FDX-H3 hardcode).
     */
    @jakarta.validation.constraints.Pattern(
            regexp = "PDF|PNG|ZPLII|EPL2|DPL|",
            message = "labelImageType must be one of PDF / PNG / ZPLII / EPL2 / DPL")
    private String labelImageType;

    /**
     * FDX-H3 — per-account FedEx labelSpecification.labelStockType. Only
     * FedEx maps this to the shipment envelope (UPS / DHL / SWSIM ignore).
     * Constrained to the common FedEx labelStockType enum values via regex;
     * empty string clears the persisted value, null keeps it.
     *
     * <p>NULL / unset → resolver falls back to {@code PAPER_4X6} (matches
     * the pre-FDX-H3 hardcode).
     */
    @jakarta.validation.constraints.Pattern(
            regexp = "PAPER_4X6|PAPER_4X6\\.75|PAPER_4X8|PAPER_4X9|PAPER_7X4\\.75|PAPER_LETTER|"
                    + "STOCK_4X6|STOCK_4X6\\.75|STOCK_4X8|STOCK_4X9_LEADING_DOC_TAB|",
            message = "labelStockType must be a supported FedEx label stock type")
    private String labelStockType;

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
