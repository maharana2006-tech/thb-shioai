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
