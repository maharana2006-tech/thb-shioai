package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * V77 — per-tenant credential override for a specific
 * {@link ExternalSystemConnection}. Empty by default; connectors that
 * derive credentials from the client code (e.g. NDS's "username =
 * clientCode + password = clientCode" rule) look here first and fall
 * back to the derived rule when no override exists.
 *
 * <p>Deleting the parent connection cascades override rows — see the
 * ON DELETE CASCADE on the FK in V77.
 */
@Entity
@Table(name = "external_system_client_login_override", uniqueConstraints =
        @UniqueConstraint(name = "uk_esclo_connection_client",
                columnNames = {"connection_id", "client_code"}))
@Data
public class ExternalSystemClientLoginOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "client_code", nullable = false, length = 64)
    private String clientCode;

    @Column(name = "username", nullable = false, length = 120)
    private String username;

    /** AES-256-GCM ciphertext (12-byte nonce prepended, base64). */
    @Column(name = "encrypted_password", nullable = false, columnDefinition = "text")
    private String encryptedPassword;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
