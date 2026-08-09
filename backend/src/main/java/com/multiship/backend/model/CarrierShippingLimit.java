package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-carrier / per-service MPS + total-weight cap catalog. Sprint 48 B2.
 *
 * <p>Ops can edit rows without a code deploy — the {@code CarrierLimitService}
 * caches lookups for 5 min. Resolution: exact (carrier, service, scope) row
 * wins; else (carrier, null service, scope) fallback; else a sensible high
 * default so missing data doesn't block shipments.
 *
 * <p>Real carrier caps change quarterly; the effective_from/until columns
 * let us version them without deleting history.
 */
@Entity
@Table(name = "carrier_shipping_limit",
        uniqueConstraints = @UniqueConstraint(name = "uk_carrier_limit_key",
                columnNames = {"carrier_code", "service_code", "scope", "effective_from"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierShippingLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UPS | FEDEX | DHL | STAMPS (canonical carrier code). */
    @Column(name = "carrier_code", nullable = false, length = 20)
    private String carrierCode;

    /** Specific service code (e.g. FEDEX_ENVELOPE, FEDEX_GROUND). Null =
     *  default cap for the whole carrier. */
    @Column(name = "service_code", length = 60)
    private String serviceCode;

    /** DOMESTIC | INTERNATIONAL | BOTH. Many carriers cap intl lower. */
    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    /** Max pieces per carrier shipment call. */
    @Column(name = "max_packages", nullable = false)
    private Integer maxPackages;

    /** Max total shipment weight in LB (across all pieces in one call). */
    @Column(name = "max_total_weight_lb", precision = 12, scale = 2)
    private BigDecimal maxTotalWeightLb;

    /**
     * Sprint 48 B11 — declared-value threshold below which the carrier
     * charges NO additional coverage / insurance surcharge. Null =
     * carrier has no free tier (or unknown). Typical values:
     * UPS Ground Saver = $20 (April 2025 change), other UPS/FedEx = $100.
     * DHL = null (Shipment Insurance is a paid VAS with no free tier).
     * Frontend warns operators when the shipment's declared value
     * exceeds this so the surcharge doesn't surprise them at bill time.
     */
    @Column(name = "free_declared_value", precision = 12, scale = 2)
    private BigDecimal freeDeclaredValue;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    /** Null = still current. */
    @Column(name = "effective_until")
    private LocalDateTime effectiveUntil;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "notes", length = 500)
    private String notes;
}
