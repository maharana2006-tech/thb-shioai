package com.multiship.backend.model;

import com.multiship.backend.service.output.DestinationType;
import com.multiship.backend.service.output.DocType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Sprint 52 — one client's routing rule for one doc type. When a bulk
 * label job (or any label pipeline) produces a document, the dispatcher
 * looks up every active row for
 * {@code (clientCode, docType)} and delivers to each.
 *
 * <p>{@link #config} carries type-specific fields as a JSON string. The
 * shape depends on {@link #destinationType}:
 * <ul>
 *   <li>{@code LOCAL_FS}: {@code {"path": "/var/labels/acme"}}</li>
 *   <li>{@code SFTP}: {@code {"host": "sftp.example.com", "port": 22,
 *       "username": "acme", "authType": "PASSWORD|KEY",
 *       "passwordSecretId": "…", "privateKeySecretId": "…",
 *       "remoteDir": "/upload"}}</li>
 *   <li>{@code PRINTER}: {@code {"host": "192.168.1.50", "port": 9100,
 *       "protocol": "RAW_9100|IPP", "queueName": null}}</li>
 * </ul>
 *
 * <p>Passwords and private keys are stored via
 * {@link com.multiship.backend.config.CryptoService}: the JSON only holds
 * a {@code passwordSecretId} / {@code privateKeySecretId} pointer to an
 * encrypted row in {@link SystemSetting}.
 */
@Entity
@Table(name = "client_output_destination",
        indexes = {
                @Index(name = "idx_cod_lookup",
                        columnList = "client_code, doc_type, active"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientOutputDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 64)
    private String clientCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 32)
    private DocType docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_type", nullable = false, length = 32)
    private DestinationType destinationType;

    /**
     * Type-specific configuration as a JSON string. Persisted as
     * Postgres {@code jsonb} — the JPA layer marshals through String and
     * lets Postgres validate the JSON at write time. Hibernate 6 auto-
     * detects the {@code jsonb} column type for a String field with
     * {@code columnDefinition = "jsonb"}.
     */
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private String config;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
