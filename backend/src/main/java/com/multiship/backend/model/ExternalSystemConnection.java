package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * V77 — one row per configured external integration (NDS Oracle, SAP
 * REST, SFTP endpoint, etc.). The framework dispatches to the
 * {@code ExternalSystemConnector} whose {@code systemType()} matches
 * {@link #systemType} and hands it {@link #configJson} parsed into
 * that connector's config-shape class.
 *
 * <p>Secrets DO NOT live here — see {@link ExternalSystemSecret}.
 * Per-tenant credential overrides go in
 * {@link ExternalSystemClientLoginOverride}.
 */
@Entity
@Table(name = "external_system_connection", uniqueConstraints =
        @UniqueConstraint(name = "uk_external_system_connection_name",
                columnNames = "name"))
@Data
public class ExternalSystemConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable lookup key ("nds-default", "sap-prod"). */
    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** Uppercase connector discriminator. Must match a registered
     *  {@code ExternalSystemConnector.systemType()}. */
    @Column(name = "system_type", nullable = false, length = 50)
    private String systemType;

    /** Off-switch. Inactive rows are loaded but skipped by the registry. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Non-secret config as JSON. Shape defined per-connector; framework
     *  never introspects. */
    @Column(name = "config_json", nullable = false, columnDefinition = "text")
    private String configJson = "{}";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
