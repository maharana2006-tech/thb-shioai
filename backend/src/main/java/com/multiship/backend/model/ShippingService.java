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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One carrier service level (UPS Ground, FedEx International Priority…).
 * The catalog is seeded once and the admin toggles what the platform offers —
 * the ShipStation pattern: everything exists, disabled services disappear
 * from resolution. scope: DOMESTIC | INTERNATIONAL | BOTH — service selection
 * uses COUNTRY inequality (a DE→FR parcel still rides an international
 * service even though it clears no customs).
 */
@Entity
@Table(name = "shipping_service",
        uniqueConstraints = @UniqueConstraint(name = "uq_shipping_service_lane",
                columnNames = {"carrier", "service_code", "origin_country"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonical carrier: UPS | FEDEX | USPS. */
    @Column(nullable = false, length = 20)
    private String carrier;

    /** The code the carrier's API expects (UPS "03", FedEx "FEDEX_GROUND"…). */
    @Column(name = "service_code", nullable = false, length = 40)
    private String serviceCode;

    @Column(nullable = false, length = 100)
    private String name;

    /** DOMESTIC | INTERNATIONAL | BOTH. */
    @Column(nullable = false, length = 15)
    private String scope;

    /**
     * ISO alpha-2 country this service is offered FROM. Carrier service
     * availability is lane-specific — UPS from DE offers Standard (intra-EU
     * ground); USPS ships only from the US. Seeded rows default to US.
     */
    @Column(name = "origin_country", length = 2)
    @Builder.Default
    private String originCountry = "US";

    /** SEEDED (starter catalog) | CARRIER_SYNC (pulled from the carrier's availability API). */
    @Column(length = 20)
    @Builder.Default
    private String source = "SEEDED";

    /** When this row was last refreshed from the carrier API (null for seeded rows). */
    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    // ===== Package limits (lb / inches) — the carrier rules this service enforces.
    // Seeded from carrier defaults on sync; admin may override. Null = use the
    // PackageMath default for the carrier. =====

    @Column(name = "max_weight_lb")
    private Integer maxWeightLb;

    @Column(name = "max_length_in")
    private Integer maxLengthIn;

    @Column(name = "max_length_girth_in")
    private Integer maxLengthGirthIn;

    /** L+girth (in) at which a Large Package/oversize surcharge applies; null = no surcharge tier. */
    @Column(name = "surcharge_length_girth_in")
    private Integer surchargeLengthGirthIn;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * FDX-G1 — true when this service ships on the carrier's Express /
     * Air fleet (FedEx FDXE, UPS 007/Air, DHL Express). False for Ground
     * services (FEDEX_GROUND, UPS 03, GROUND_HOME_DELIVERY) and every
     * USPS/SWSIM row (SWSIM manifest has no per-request fleet code).
     *
     * <p>{@link com.multiship.backend.service.ManifestServiceImpl} reads
     * this flag to split closeOutDay calls by fleet — pre-FDX-G, FedEx
     * always sent carrierCode="FDXG" which silently manifested Express
     * labels as Ground, leaving the Express carrier's manifest empty.
     * Backfilled by Flyway V25.
     */
    @Column(name = "is_express", nullable = false)
    @Builder.Default
    private boolean express = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    /**
     * Sprint 52 PR X — false when this service intentionally accepts only
     * YOUR_PACKAGING (CUSTOM presets) and no carrier-branded packaging.
     * Ground-family services (FEDEX_GROUND, GROUND_HOME_DELIVERY, UPS 03,
     * USPS Ground Advantage) fall in this bucket per V30 seed. Distinguishes
     * "admin hasn't linked any presets" (flag=true, empty service_package
     * pool → 'config incomplete') from "this service is CUSTOM-only by
     * design" (flag=false, empty pool by design → no warning; branded
     * presets hidden from the link modal). Admin can flip either way via
     * the ShippingServicesPage per-service toggle.
     *
     * <p>Consumed by: FE dropdown filter (compatiblePresetIds), guard,
     * ShippingServicesPage per-row badge, link modal preset filter.
     */
    @Column(name = "branded_packaging_allowed", nullable = false)
    @Builder.Default
    private boolean brandedPackagingAllowed = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
