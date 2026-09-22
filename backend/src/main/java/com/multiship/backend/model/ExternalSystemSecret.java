package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * V77 — encrypted secret keyed to a specific {@link ExternalSystemConnection}
 * row. All values encrypted via {@code CryptoService} (AES-256-GCM);
 * plaintext never touches disk or logs.
 *
 * <p>Deleting the parent connection cascades secret rows away — see the
 * ON DELETE CASCADE on the FK in V77.
 */
@Entity
@Table(name = "external_system_secret", uniqueConstraints =
        @UniqueConstraint(name = "uk_ess_connection_key",
                columnNames = {"connection_id", "secret_key"}))
@Data
public class ExternalSystemSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    /** Per-connector secret name (e.g. "productionPassword",
     *  "oauthClientSecret"). Read via
     *  {@code ExternalSystemConfigService.getSecret(connectionId, key)}. */
    @Column(name = "secret_key", nullable = false, length = 120)
    private String secretKey;

    /** AES-256-GCM ciphertext (12-byte nonce prepended, base64). */
    @Column(name = "encrypted_value", nullable = false, columnDefinition = "text")
    private String encryptedValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
