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
 * Per-client package-code alias: maps the ERP's own package SKU
 * (e.g. "ACME-BOX-A") to a platform {@link PackagePreset}, optionally
 * scoped by destination country / region.
 *
 * <p>Uniqueness on {@code (clientCode, erpCode, destCountry, destRegion)}
 * is enforced at the service layer; no DB unique constraint (see
 * ClientShipviaCodeMap doc for the same Postgres-NULLs rationale).
 */
@Entity
@Table(name = "client_package_code_map",
        indexes = @Index(name = "idx_client_package_code_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientPackageCodeMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** The raw package code the ERP sends (e.g. "ACME-BOX-A"). */
    @Column(name = "erp_code", nullable = false, length = 40)
    private String erpCode;

    /** FK to {@link PackagePreset#getId()}. */
    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    /** ISO-2 destination country (nullable = "any country in the region"). */
    @Column(name = "dest_country", length = 2)
    private String destCountry;

    /** Destination region name (nullable = "any region"). */
    @Column(name = "dest_region", length = 40)
    private String destRegion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
