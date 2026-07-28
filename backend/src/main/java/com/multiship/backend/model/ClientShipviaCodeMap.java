package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * Per-client shipvia alias: maps an ERP-side ship-method code to a platform
 * {@link ShippingService}, optionally scoped by destination country or
 * region. Distinct from {@link ShipViaMapping} in that this is a direct
 * alias (no rules) — used at order intake to translate the raw code.
 *
 * <p>Destination scope makes the alias per-lane so a single ERP shipvia
 * code (e.g. "P80") can resolve to different services in different
 * destinations. Both {@code destCountry} and {@code destRegion} are
 * nullable: null in both = "any destination" (the fallback).
 *
 * <p>Uniqueness is enforced at the service layer (find-then-save on
 * {@code (clientCode, erpCode, destCountry, destRegion)}). We deliberately
 * do NOT declare a DB unique constraint that includes the nullable
 * destination columns — Postgres treats NULLs as distinct in unique keys
 * by default, which would let two duplicate "any-destination" rows coexist.
 * The service preflight does the right thing regardless.
 */
@Entity
@Table(name = "client_shipvia_code_map",
        indexes = @Index(name = "idx_client_shipvia_code_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientShipviaCodeMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** The raw ship-method code the ERP sends (e.g. "P80"). */
    @Column(name = "erp_code", nullable = false, length = 40)
    private String erpCode;

    /** FK to {@link ShippingService#getId()}. */
    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    /** ISO-2 destination country (nullable = "any country in the region"). */
    @Column(name = "dest_country", length = 2)
    private String destCountry;

    /** Destination region name (e.g. "North America"; nullable = "any region"). */
    @Column(name = "dest_region", length = 40)
    private String destRegion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
