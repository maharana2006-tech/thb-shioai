package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A4.1 — per-provider config row. Non-secret values live in {@code configValue}
 * (plaintext); secrets live in {@code encryptedValue} (round-tripped through
 * {@link com.multiship.backend.config.CryptoService}). {@code isSecret} discriminates,
 * enforced at the DB level by {@code ck_mail_config_value_shape}.
 */
@Entity
@Table(name = "mail_config",
        uniqueConstraints = @UniqueConstraint(name = "ux_mail_config_provider_key",
                columnNames = {"provider_id", "config_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "config_key", nullable = false, length = 60)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "encrypted_value", columnDefinition = "TEXT")
    private String encryptedValue;

    @Column(name = "is_secret", nullable = false)
    private boolean isSecret;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
