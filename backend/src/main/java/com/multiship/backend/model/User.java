package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Builder.Default;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 100)
    private String username;

    @Column(unique = true, nullable = false, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(nullable = false, length = 100)
    private String role;

    /**
     * Sprint 50 Tier 0.5 PR A — formalises the User↔Client tenant linkage
     * that was previously derived from the username via convention
     * ({@code OrderAccessEvaluator.tenantIdOf}).
     *
     * <p>Nullable during rollout: legacy ADMIN + USER rows (org-wide operator
     * scope) keep {@code client_code = null}; TENANT rows are backfilled from
     * uppercase(username) by the V3 Flyway migration. PR E adds the semantic
     * gate — USER-with-clientCode becomes tenant-scoped; USER-without-clientCode
     * stays org-wide for backward-compat; ADMIN stays org-wide unconditionally.
     * PR F will NOT-NULL the column for USER + TENANT roles.
     */
    @Column(name = "client_code", length = 50)
    private String clientCode;

    /**
     * Sprint 50 Tier 0.5 PR D — login gate. Public-signup users start
     * {@code false} and must consume the verification email. Invite-created
     * users start {@code true} (invite acceptance IS the verification).
     * Legacy rows are backfilled to {@code true} so existing operators
     * aren't locked out on deploy.
     */
    @Column(name = "email_verified", nullable = false)
    @Default
    private Boolean emailVerified = true;

    /**
     * Sprint 50 Tier 0.5 PR D — one-shot token for the "click the link
     * in your email" step. Null when the account is verified. The token
     * is opaque high-entropy; the whole row expires with the user's
     * email_verify_expires_at (24h).
     */
    @Column(name = "email_verify_token", length = 100)
    private String emailVerifyToken;

    @Column(name = "email_verify_expires_at")
    private LocalDateTime emailVerifyExpiresAt;

    /**
     * Sprint 50 Tier 0.5 PR E — admin-driven soft delete. Non-null blocks
     * login (403 ACCOUNT_DEACTIVATED). Distinct from an unverified account
     * so an accidentally-revoked user gets a clear message rather than
     * "verify your email."
     */
    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    /** Which admin performed the deactivation (audit convenience). */
    @Column(name = "deactivated_by", length = 100)
    private String deactivatedBy;

    // ===== Carrier Integration Fields =====
    @Column(name = "preferred_carrier", length = 50)
    private String preferredCarrier;

    @Column(name = "carrier_client_id", length = 255)
    private String carrierClientId;

    @Column(name = "carrier_client_secret", length = 255)
    private String carrierClientSecret;

    @Column(name = "carrier_account_number", length = 100)
    private String carrierAccountNumber;

    @Column(name = "carrier_access_token", columnDefinition = "TEXT")
    private String carrierAccessToken;

    @Column(name = "carrier_token_expires_at")
    private LocalDateTime carrierTokenExpiresAt;

    @Column(name = "carrier_connected")
    @Default
    private Boolean carrierConnected = false;

    @Column(name = "carrier_connected_at")
    private LocalDateTime carrierConnectedAt;

    @Column(name = "carrier_environment", length = 20)
    @Default
    private String carrierEnvironment = "SANDBOX";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Helper method to check if carrier token is expired
    public boolean isCarrierTokenExpired() {
        if (carrierTokenExpiresAt == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(carrierTokenExpiresAt);
    }

    // Helper method to check if carrier is connected
    public boolean isCarrierConnected() {
        return Boolean.TRUE.equals(carrierConnected) && carrierClientId != null;
    }

    public boolean isSpecificCarrierConnected(String carrierCode) {
        return isCarrierConnected()
                && carrierCode != null
                && preferredCarrier != null
                && carrierCode.equalsIgnoreCase(preferredCarrier);
    }

    public String getCarrierDisplayName() {
        if (preferredCarrier == null) {
            return "No Carrier";
        }

        return switch (preferredCarrier.toUpperCase()) {
            case "P80", "UPS" -> "UPS";
            case "F77", "FEDEX" -> "FedEx";
            case "L01", "USPS" -> "USPS";
            default -> preferredCarrier;
        };
    }
}
