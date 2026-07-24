package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Per-client rate-shopping strategy + dispatch cutoff. One row per client,
 * keyed by client_code. Absent row = platform defaults (CHEAPEST, no cutoff).
 *
 * Cutoff is a soft flag: a shipment created after the cutoff still succeeds,
 * but the response carries dispatchNextBusinessDay=true so the WMS knows the
 * label won't go out today.
 *
 * rateStrategy = FIXED requires fixedServiceId to name a service in the
 * client's allowlist; the resolution service refuses to save a FIXED policy
 * without one.
 */
@Entity
@Table(name = "client_shipping_policy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientShippingPolicy {

    public static final String STRATEGY_CHEAPEST = "CHEAPEST";
    public static final String STRATEGY_FASTEST = "FASTEST";
    public static final String STRATEGY_FIXED = "FIXED";

    /** client_code is the natural PK; there is one policy row per client. */
    @Id
    @Column(name = "client_code", length = 50)
    private String clientCode;

    /** CHEAPEST | FASTEST | FIXED. */
    @Column(name = "rate_strategy", nullable = false, length = 10)
    @Builder.Default
    private String rateStrategy = STRATEGY_CHEAPEST;

    /** Required when rateStrategy = FIXED; the service the client always uses. */
    @Column(name = "fixed_service_id")
    private Long fixedServiceId;

    /** Local dispatch cutoff. Null = no cutoff configured. */
    @Column(name = "cutoff_time")
    private LocalTime cutoffTime;

    /** IANA time zone id for cutoffTime (e.g. America/New_York). */
    @Column(name = "cutoff_tz", length = 60)
    private String cutoffTz;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
