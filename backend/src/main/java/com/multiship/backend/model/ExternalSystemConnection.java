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

    // ── V89 writeback flags ────────────────────────────────────────────
    // One boolean per payload field. Same flag gates BOTH the generate-
    // side push AND the void-side clear: if writeback_carrier is off,
    // the carrier code is neither sent on generate nor nulled on void.
    // All default false; admin enables from /settings/external-systems.

    @Column(name = "writeback_tracking", nullable = false, columnDefinition = "boolean default false")
    private Boolean writebackTracking = false;

    @Column(name = "writeback_ship_date", nullable = false, columnDefinition = "boolean default false")
    private Boolean writebackShipDate = false;

    @Column(name = "writeback_status", nullable = false, columnDefinition = "boolean default false")
    private Boolean writebackStatus = false;

    @Column(name = "writeback_carrier", nullable = false, columnDefinition = "boolean default false")
    private Boolean writebackCarrier = false;

    @Column(name = "writeback_service", nullable = false, columnDefinition = "boolean default false")
    private Boolean writebackService = false;

    @Column(name = "writeback_freight", nullable = false, columnDefinition = "boolean default false")
    private Boolean writebackFreight = false;

    // ── V90 source / channel dispatch gates ────────────────────────────
    // Filter WHETHER writeback fires for a given payload, based on the
    // origin (source) or shipping channel of the order. Default TRUE so
    // existing rows keep firing across all surfaces unless an admin
    // narrows the connection.

    @Column(name = "writeback_source_manual", nullable = false, columnDefinition = "boolean default true")
    private Boolean writebackSourceManual = true;

    @Column(name = "writeback_source_bulk", nullable = false, columnDefinition = "boolean default true")
    private Boolean writebackSourceBulk = true;

    @Column(name = "writeback_source_api", nullable = false, columnDefinition = "boolean default true")
    private Boolean writebackSourceApi = true;

    @Column(name = "writeback_channel_d2c", nullable = false, columnDefinition = "boolean default true")
    private Boolean writebackChannelD2c = true;

    @Column(name = "writeback_channel_b2b", nullable = false, columnDefinition = "boolean default true")
    private Boolean writebackChannelB2b = true;
}
