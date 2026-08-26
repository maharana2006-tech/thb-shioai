package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierAccountRefDTO {
    private Long id;
    private String accountNumber;
    private String carrierCode;
    private String accountName;
    private String customerNo;
    private String environment;
    private Boolean isDefault;
    /** Default account for the linked client (customerNo). */
    private Boolean clientDefault;
    private Boolean active;
    /** True when credentials are filled and the account is usable for generation. */
    private Boolean complete;
    /** Masked hint of the client id, never the secret. */
    private String clientIdPreview;
    /** Result of the last credential check against the carrier API; null = never checked. */
    private Boolean verified;
    private LocalDateTime lastVerifiedAt;
    /** Labels generated with this account and when it last shipped. */
    private Long labelsGenerated;
    private LocalDateTime lastUsedAt;
    /** International-shipment defaults; wire values match the frontend's
     *  {@code utils/customsOptions.ts}. Nullable — carriers apply their own
     *  defaults when unset. */
    private String shippingPurpose;
    private String clearanceOption;
    /** F6-B2 — per-account billing currency override. ISO 4217. NULL means
     *  "use carrier home currency" (USPS/UPS/FedEx → USD, DHL → EUR). */
    private String currency;
    /** FDX-H1 — per-account default pickupType (FedEx only; UPS/DHL/USPS
     *  ignore). NULL means USE_SCHEDULED_PICKUP (pre-FDX-H1 hardcode).
     *  Values: REGULAR_PICKUP | REQUEST_COURIER | DROP_BOX |
     *  BUSINESS_SERVICE_CENTER | STATION | USE_SCHEDULED_PICKUP. */
    private String pickupType;
    /** UPS-4a — per-account UPS LabelImageFormat (UPS only; other carriers
     *  ignore). NULL means GIF (pre-UPS-4a hardcode). Values: GIF | PDF |
     *  PNG | ZPL | EPL. */
    private String labelImageFormat;
    /** Third-party billing address (default, when clearanceOption=THIRD_PARTY).
     *  Nullable — per-shipment overrides live on the Shipment row. */
    private String thirdPartyAccount;
    private String thirdPartyName;
    private String thirdPartyAddress1;
    private String thirdPartyCity;
    private String thirdPartyState;
    private String thirdPartyPostcode;
    private String thirdPartyCountry;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
