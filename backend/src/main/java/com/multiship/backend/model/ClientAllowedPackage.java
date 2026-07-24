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
 * Per-client allowlist of {@link PackagePreset}s. Same shape as
 * {@link ClientAllowedService}: explicit assignment, one default per client.
 *
 * The old platform-wide is_default on PackagePreset is superseded by the
 * per-client default here; PackagesPage keeps the global preset flag for
 * back-compat but resolution uses this row first.
 */
@Entity
@Table(name = "client_allowed_package",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_allowed_package",
                columnNames = {"client_code", "preset_id"}),
        indexes = {
                @Index(name = "idx_client_allowed_package_client", columnList = "client_code"),
                @Index(name = "idx_client_allowed_package_preset", columnList = "preset_id"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    /** The client's default package. At most one row per client is true. */
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
