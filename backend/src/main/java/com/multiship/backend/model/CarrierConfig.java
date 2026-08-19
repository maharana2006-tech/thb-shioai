package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "carrier_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ship_via_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ShipVia shipVia;

    @Column(name = "tenant_id", length = 50)
    private String tenantId;

    @Column(name = "carrier_code", nullable = false, length = 50)
    private String carrierCode;

    @Column(name = "carrier_name", nullable = false, length = 100)
    private String carrierName;

    @Column(name = "client_id", length = 255)
    private String clientId;

    // Sprint 49 Tier 1: encrypted at rest via EncryptedStringConverter.
    // Column widened to 512 to fit the base64 nonce||GCM ciphertext||tag
    // wire format (~380 chars for a 255-char plaintext).
    @Column(name = "client_secret", length = 512)
    @jakarta.persistence.Convert(converter = com.multiship.backend.config.EncryptedStringConverter.class)
    private String clientSecret;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "account_code", length = 50)
    private String accountCode;

    @Column(name = "is_default")
    @Default
    private Boolean isDefault = false;

    // Sprint 51 security fix — the carrier OAuth access token is a live
    // bearer credential (prints labels billed to the account) just like
    // client_secret, so it gets the same at-rest AES-GCM encryption. TEXT
    // column already fits the base64 nonce||ciphertext||tag wire format.
    @Column(name = "access_token", columnDefinition = "TEXT")
    @jakarta.persistence.Convert(converter = com.multiship.backend.config.EncryptedStringConverter.class)
    private String accessToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "environment", length = 20)
    @Default
    private String environment = "SANDBOX";

    @Column(name = "is_active")
    @Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Boolean getActive() {
        return isActive;
    }

    public void setActive(Boolean active) {
        this.isActive = active;
    }
}
