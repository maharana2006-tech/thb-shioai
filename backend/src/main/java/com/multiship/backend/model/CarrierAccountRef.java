package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Reference book of carrier accounts, keyed by account number.
 * Orders that carry only an account number resolve their credentials here
 * (scenario 2 saves fill-ups into this table so future orders auto-generate),
 * and exactly one row flagged isDefault serves scenario 3.
 */
@Entity
@Table(name = "carrier_account_ref",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_ref_number_carrier",
                columnNames = {"account_number", "carrier_code"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierAccountRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, length = 100)
    private String accountNumber;

    @Column(name = "carrier_code", nullable = false, length = 50)
    private String carrierCode;

    @Column(name = "account_name", length = 100)
    private String accountName;

    @Column(name = "client_id", length = 255)
    private String clientId;

    // Sprint 49 Tier 1: encrypted at rest via EncryptedStringConverter.
    // Column widened to 512 to fit the base64 nonce||GCM ciphertext||tag
    // wire format (~380 chars for a 255-char plaintext).
    @Column(name = "client_secret", length = 512)
    @jakarta.persistence.Convert(converter = com.multiship.backend.config.EncryptedStringConverter.class)
    private String clientSecret;

    @Column(name = "environment", length = 20)
    @Default
    private String environment = "SANDBOX";

    @Column(name = "customer_no", length = 50)
    private String customerNo;

    /** Default account for the linked client (customerNo); at most one per client. */
    @Column(name = "client_default")
    @Default
    private Boolean clientDefault = false;

    @Column(name = "is_default")
    @Default
    private Boolean isDefault = false;

    @Column(name = "active")
    @Default
    private Boolean active = true;

    /** Result of the last credential verification against the carrier API. */
    @Column(name = "verified")
    private Boolean verified;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    /**
     * International-shipment defaults captured per account. Both are optional
     * (nullable) — carriers apply their own defaults when unset. Values are
     * validated at the frontend against a fixed enum / per-carrier list so
     * these columns only ever see known wire codes.
     *
     * shippingPurpose: SALE | GIFT | SAMPLE | REPAIR_AND_RETURN | DOCUMENTS |
     *   MERCHANDISE | PERSONAL_USE | RETURN
     * clearanceOption: per-carrier — UPS SENDER/RECEIVER/THIRD_PARTY,
     *   FEDEX SENDER/RECIPIENT/THIRD_PARTY, USPS DDU/DDP.
     */
    @Column(name = "shipping_purpose", length = 30)
    private String shippingPurpose;

    @Column(name = "clearance_option", length = 30)
    private String clearanceOption;

    /**
     * F6-B2 — per-account billing currency override. ISO 4217 3-letter code.
     * When NULL, the resolver falls back to the carrier's hardcoded home
     * currency (USPS/UPS/FedEx → USD; DHL → EUR). When set, this value is
     * authoritative for anything this account bills — regardless of client
     * currency or carrier default. If it differs from the client's currency,
     * F6-D converts declared value / commodities / insured value / freight
     * via FxRateService before the connector envelope is built.
     */
    @Column(name = "currency", length = 3)
    private String currency;

    /**
     * FDX-H1 — per-account default pickupType. Only FedEx currently maps
     * this to the wire; UPS / DHL / SWSIM connectors ignore the value.
     *
     * <p>FedEx pickupType enum:
     * <ul>
     *   <li>{@code REGULAR_PICKUP} — standard scheduled pickup</li>
     *   <li>{@code REQUEST_COURIER} — on-demand courier request</li>
     *   <li>{@code DROP_BOX} — shipper drops at a FedEx drop box</li>
     *   <li>{@code BUSINESS_SERVICE_CENTER} — shipper drops at a FedEx BSC</li>
     *   <li>{@code STATION} — shipper drops at a FedEx station</li>
     *   <li>{@code USE_SCHEDULED_PICKUP} — shipper has a standing daily
     *       pickup; the default when this column is NULL</li>
     * </ul>
     *
     * <p>Return labels bypass this column entirely — the FedEx connector
     * force-sets {@code CONTACT_FEDEX_TO_SCHEDULE} for {@code isReturn=true}
     * shipments so the customer doesn't need a scheduled pickup.
     *
     * <p>NULL → resolver falls back to {@code USE_SCHEDULED_PICKUP} (matches
     * the pre-FDX-H1 behavior). Deliberately not backfilled — silently
     * flipping every existing account would misclassify shippers whose
     * ops mode changes day-to-day.
     */
    @Column(name = "pickup_type", length = 30)
    private String pickupType;

    /**
     * UPS-4a — per-account label file format. Originally UPS-only; widened
     * to also drive DHL Express and USPS/Stamps (FedEx uses the separate
     * {@link #fedexLabelImageType} / {@link #fedexLabelStockType} pair
     * instead — FedEx has a second stock-size axis these carriers don't).
     *
     * <ul>
     *   <li>UPS LabelImageFormat: {@code GIF} (default) | {@code PDF} |
     *       {@code PNG} | {@code ZPL} | {@code EPL}.</li>
     *   <li>DHL Express label encoding: {@code PDF} (default) | {@code ZPL}.</li>
     *   <li>USPS/Stamps SWSIM ImageType: {@code PNG} | {@code PDF} |
     *       {@code GIF} | {@code JPG}. NULL leaves the element off the
     *       SOAP envelope entirely (SWSIM's own default applies).</li>
     * </ul>
     *
     * <p>NULL → each connector's pre-existing hardcoded default (UPS GIF,
     * DHL PDF, USPS unset/SWSIM-default). Deliberately not backfilled —
     * silently flipping every account would break operators tuned around
     * the existing format.
     */
    @Column(name = "label_image_format", length = 10)
    private String labelImageFormat;

    /**
     * FDX-H3 — per-account FedEx labelSpecification.imageType. Only FedEx
     * consumes this; other connectors ignore it.
     *
     * <p>FedEx imageType enum: {@code PDF} | {@code PNG} | {@code ZPLII} |
     * {@code EPL2} | {@code DPL}.
     *
     * <p>NULL → resolver falls back to {@code PDF} (matches the pre-FDX-H3
     * hardcode). Deliberately not backfilled — see V28 migration.
     */
    @Column(name = "fedex_label_image_type", length = 10)
    private String fedexLabelImageType;

    /**
     * FDX-H3 — per-account FedEx labelSpecification.labelStockType. Only
     * FedEx consumes this; other connectors ignore it.
     *
     * <p>FedEx labelStockType enum (common values): {@code PAPER_4X6} |
     * {@code PAPER_4X6.75} | {@code PAPER_4X8} | {@code PAPER_4X9} |
     * {@code PAPER_7X4.75} | {@code PAPER_LETTER} | {@code STOCK_4X6} |
     * {@code STOCK_4X6.75} | {@code STOCK_4X8} |
     * {@code STOCK_4X9_LEADING_DOC_TAB}.
     *
     * <p>NULL → resolver falls back to {@code PAPER_4X6} (matches the
     * pre-FDX-H3 hardcode). Deliberately not backfilled — see V28 migration.
     */
    @Column(name = "fedex_label_stock_type", length = 30)
    private String fedexLabelStockType;

    /**
     * Third-party billing party — used only when clearanceOption is THIRD_PARTY
     * (UPS / FedEx). Acts as the ACCOUNT-LEVEL DEFAULT for every shipment on
     * this account; per-shipment overrides live on the Shipment row (follow-up
     * PR). Semantically nullable throughout — carriers reject third-party
     * shipments that omit at least the account number, but we let the row save
     * with partial values so the operator can fill in what they know.
     *
     * Postal codes vary by country; keep long enough for GB-style codes.
     * Country is ISO-2 (10 chars matches the {@code shipFrom.country} width).
     */
    @Column(name = "third_party_account", length = 100)
    private String thirdPartyAccount;

    @Column(name = "third_party_name", length = 255)
    private String thirdPartyName;

    @Column(name = "third_party_address1", length = 255)
    private String thirdPartyAddress1;

    @Column(name = "third_party_city", length = 100)
    private String thirdPartyCity;

    @Column(name = "third_party_state", length = 50)
    private String thirdPartyState;

    @Column(name = "third_party_postcode", length = 20)
    private String thirdPartyPostcode;

    @Column(name = "third_party_country", length = 10)
    private String thirdPartyCountry;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public boolean isComplete() {
        return hasText(accountNumber) && hasText(clientId) && hasText(clientSecret);
    }
}
