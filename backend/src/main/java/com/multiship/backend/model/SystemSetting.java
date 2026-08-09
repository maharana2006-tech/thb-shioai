package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Sprint 49 Tier 0 — DB-backed admin-managed secrets.
 *
 * <p>Overlays application.properties for keys the admin should be able
 * to rotate at runtime (currently: {@code openai.api-key}). Values are
 * stored encrypted via
 * {@link com.multiship.backend.config.CryptoService}; the DB never sees
 * plaintext.
 *
 * <p>The setting {@code key} matches the corresponding property name so
 * consumers can read either source uniformly.
 */
@Entity
@Table(name = "system_settings")
@Data
public class SystemSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Property-style key, e.g. {@code openai.api-key}. Unique per row.
     */
    @Column(name = "setting_key", nullable = false, unique = true, length = 200)
    private String key;

    /**
     * Base64( nonce || AES-GCM ciphertext || tag ). See {@link com.multiship.backend.config.CryptoService}.
     */
    @Column(name = "encrypted_value", columnDefinition = "text")
    private String encryptedValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);

    /**
     * Username of the admin who last updated this setting, for audit.
     */
    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
