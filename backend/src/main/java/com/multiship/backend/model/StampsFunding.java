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

/** One postage top-up of a Stamps.com USPS account (V139). */
@Entity
@Table(name = "stamps_funding")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StampsFunding {

    public static final String ADDED = "ADDED";
    public static final String FAILED = "FAILED";
    /** V140 — reserved (key recorded) before the Stamps.com charge; settled to
     *  ADDED/FAILED after. A row left PENDING means the charge call didn't
     *  return (crash/timeout); resubmitting the same key recovers it. */
    public static final String PENDING = "PENDING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "carrier_account_ref_id", nullable = false)
    private Long carrierAccountRefId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String status;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    private String message;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
