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

    @Column(name = "client_secret", length = 255)
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
