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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A reusable package definition: either CARRIER packaging (UPS Letter,
 * FedEx Pak — the carrier fixes the dimensions) or a CUSTOM box the
 * warehouse uses. Exactly one preset is the default; label generation uses
 * it for package type + dimensions (weight still comes from the order).
 */
@Entity
@Table(name = "package_preset")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackagePreset {

    public static final String OWNER_PLATFORM = "PLATFORM";
    public static final String OWNER_CLIENT = "CLIENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    /**
     * PLATFORM presets are visible to every client and gated by
     * {@link ClientAllowedPackage}. CLIENT presets are private to the owning
     * client and auto-allowed for them (no ClientAllowedPackage row needed).
     * Mirrors the {@link Warehouse} ownership pattern.
     *
     * <p>Column is nullable at the DB level: a NULL value is treated as
     * {@code PLATFORM} everywhere (readers use {@code equals} which returns
     * false for null; writers default null → PLATFORM in savePreset). This
     * lets Hibernate {@code ddl-auto=update} add the column to an existing
     * populated table without a data migration — pre-Phase-5 rows land with
     * NULL, which is exactly the PLATFORM they always were.
     */
    @Column(name = "owner_type", length = 10)
    @Builder.Default
    private String ownerType = OWNER_PLATFORM;

    /** Populated only when ownerType=CLIENT; null for PLATFORM presets. */
    @Column(name = "owner_client_code", length = 50)
    private String ownerClientCode;

    /** CARRIER (carrier-defined packaging) | CUSTOM (own box). */
    @Column(nullable = false, length = 10)
    private String kind;

    /** Carrier package code for CARRIER kind (e.g. UPS "01" Letter, FedEx "FEDEX_PAK"). */
    @Column(name = "carrier_package_code", length = 40)
    private String carrierPackageCode;

    /** Restrict to one carrier (UPS/FEDEX/USPS); null = usable with any. */
    @Column(length = 20)
    private String carrier;

    /**
     * For CARRIER packaging: the origin country it's offered from (USPS Flat
     * Rate is US-only, FedEx One Rate is US-domestic, 10/25KG boxes are
     * international). Null for CUSTOM boxes (usable anywhere).
     */
    @Column(name = "origin_country", length = 2)
    private String originCountry;

    /** CUSTOM (admin-created box) | CARRIER_SYNC (built-in availability) | CARRIER_API (live carrier response).
     *  Legacy demo rows used SEEDED; that path no longer produces data. */
    @Column(length = 20)
    @Builder.Default
    private String source = "CUSTOM";

    /** DOMESTIC | INTERNATIONAL | BOTH — the lanes this packaging is valid on. */
    @Column(length = 15)
    @Builder.Default
    private String scope = "BOTH";

    // External dimensions — what the carrier measures (rating, DIM weight, girth).
    @Column(precision = 8, scale = 2) private BigDecimal length;
    @Column(precision = 8, scale = 2) private BigDecimal width;
    @Column(precision = 8, scale = 2) private BigDecimal height;

    // Internal dimensions — usable space inside (ShipperHQ pattern; packing
    // math once product dims exist). Optional.
    @Column(name = "internal_length", precision = 8, scale = 2) private BigDecimal internalLength;
    @Column(name = "internal_width", precision = 8, scale = 2) private BigDecimal internalWidth;
    @Column(name = "internal_height", precision = 8, scale = 2) private BigDecimal internalHeight;

    /** Packaging material cost per box (reporting). */
    @Column(name = "box_cost", precision = 8, scale = 2) private BigDecimal boxCost;

    /** Flat-rate carrier packaging: price fixed, DIM weight does not apply. */
    @Column(name = "flat_rate") private Boolean flatRate;

    /** Auto-pick tie-break: lower = preferred. */
    @Column(name = "sort_order") private Integer sortOrder;

    /** IN | CM */
    @Column(name = "dim_unit", nullable = false, length = 5)
    @Builder.Default
    private String dimUnit = "IN";

    @Column(name = "max_weight", precision = 8, scale = 2)
    private BigDecimal maxWeight;

    /** LB | KG */
    @Column(name = "weight_unit", nullable = false, length = 5)
    @Builder.Default
    private String weightUnit = "LB";

    /** Empty-box weight added to the item weight on the label. */
    @Column(name = "tare_weight", precision = 8, scale = 2)
    private BigDecimal tareWeight;

    /**
     * Dimensional-weight divisor for this preset. When set, DIM weight =
     * (L × W × H) / dimDivisor (in inches / lb); the validator rejects the
     * shipment if DIM weight exceeds {@link #maxWeight}. Typical values:
     * FedEx Ground/Express = 139, UPS Air = 166, DHL metric = 5000. Null =
     * DIM check is skipped for this preset (flat-rate boxes typically skip
     * DIM per carrier rules).
     */
    @Column(name = "dim_divisor")
    private Integer dimDivisor;

    /**
     * Max girth (L + 2×(W+H)) in {@link #dimUnit}. When set, the validator
     * rejects the shipment if the requested dimensions push girth above
     * this cap. Typical: FedEx Ground/Home = 165 in, UPS Ground = 165 in,
     * USPS Priority = 108 in. Null = girth check is skipped.
     */
    @Column(name = "max_girth", precision = 8, scale = 2)
    private BigDecimal maxGirth;

    /**
     * How the validator treats rule violations for this preset:
     * {@code HARD} (default) — reject the shipment with 422;
     * {@code SOFT} — allow the shipment through with a warning
     * surfaced to the operator. Ops can flip a preset from HARD to
     * SOFT to override a rule in the field without a code deploy.
     */
    @Column(name = "enforcement", length = 10)
    @Builder.Default
    private String enforcement = "HARD";

    /**
     * Wrapper Booleans (not primitives): Jackson binds this entity through the
     * all-args creator, and a missing JSON property arrives as null — a
     * primitive here turns "field not sent" into a 400/401. @JsonProperty
     * keeps the API name "default" for both read and write.
     */
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonProperty("default")
    private Boolean isDefault = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
