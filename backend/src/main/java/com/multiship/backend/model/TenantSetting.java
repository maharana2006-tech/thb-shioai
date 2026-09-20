package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Per-tenant plaintext key-value preference row. First consumer:
 * {@code enabledChannels} = {@code D2C} | {@code B2B} | {@code D2C,B2B}
 * — gates external API + WMS pull with 403 TENANT_CHANNEL_NOT_ENABLED
 * when the incoming order's channel isn't allowed.
 *
 * <p>NOT a full Tenant entity — just a home for per-tenant preferences
 * that don't naturally belong to any existing table. Any future setting
 * (default carrier, notification opt-outs, feature flags) lands here
 * without schema churn.
 *
 * <p>Sibling of {@link SystemSetting} (global, encrypted secrets); this
 * row is per-tenant and plaintext by design.
 */
@Entity
@Table(name = "tenant_settings", uniqueConstraints =
        @UniqueConstraint(name = "uk_tenant_settings_tenant_key",
                          columnNames = {"tenant_code", "setting_key"}))
@Data
public class TenantSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_code", nullable = false, length = 64)
    private String tenantCode;

    /** Property-style key, camelCase to line up with FE JSON. */
    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    /** Plaintext value. Encoding per-key — see {@code TenantSettingsService}
     *  for consumer-specific formats. */
    @Column(name = "setting_value", columnDefinition = "text", nullable = false)
    private String settingValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);

    /** Username of the last admin to write; null for system-seeded rows. */
    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
