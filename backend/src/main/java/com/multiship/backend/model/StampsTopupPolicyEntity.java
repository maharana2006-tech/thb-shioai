package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * D5 — one row per Stamps {@code carrier_account_ref} that opts into
 * automatic postage top-ups. Populated manually today; F6 admin UI
 * lands later.
 */
@Entity
@Table(name = "stamps_topup_policy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StampsTopupPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "carrier_account_ref_id", nullable = false, unique = true)
    private Long carrierAccountRefId;

    /** Available balance at or below this amount triggers a top-up. */
    @Column(name = "threshold_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal thresholdAmount;

    /** Fixed top-up amount fired when the threshold is crossed. */
    @Column(name = "topup_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal topupAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Recipient for STAMPS.LOW_FUNDS_ALERT notifications. */
    @Column(name = "alert_email", length = 255)
    private String alertEmail;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_polled_at")
    private LocalDateTime lastPolledAt;

    @Column(name = "last_topped_up_at")
    private LocalDateTime lastToppedUpAt;

    @Column(name = "last_balance", precision = 12, scale = 2)
    private BigDecimal lastBalance;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
