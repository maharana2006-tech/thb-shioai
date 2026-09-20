-- V73 - Tenant channels feature: per-tenant plaintext key-value settings.
--
-- Minimal (tenant_code, setting_key, setting_value) store — NOT a full
-- Tenant entity, just a home for per-tenant preferences that don't
-- naturally live anywhere else.
--
-- First consumer: enabledChannels = 'D2C' | 'B2B' | 'D2C,B2B' — gates
-- external API + WMS pull. Missing setting = order intake 403 with
-- TENANT_CHANNEL_NOT_ENABLED (force-picking default; operator MUST
-- configure at /settings/system before customer integrations work).
--
-- Room to grow: any future per-tenant preference (default carrier,
-- feature flags, notification opt-outs) lands in the same table without
-- schema churn.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('public.tenant_settings') IS NULL THEN
        CREATE TABLE public.tenant_settings (
            id             BIGSERIAL PRIMARY KEY,
            tenant_code    VARCHAR(64)  NOT NULL,
            -- Property-style key, e.g. 'enabledChannels'. Case-sensitive
            -- match; camelCase to line up with FE JSON payloads.
            setting_key    VARCHAR(120) NOT NULL,
            -- Plaintext string value. Encoding is per-key (this file
            -- documents each new key as consumers are added). For
            -- enabledChannels: comma-separated 'D2C' | 'B2B' | 'D2C,B2B'.
            setting_value  TEXT         NOT NULL,
            created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            -- Username of the last admin to write; nullable for
            -- system-seeded rows (none today, but leaves room).
            updated_by     VARCHAR(200),
            CONSTRAINT uk_tenant_settings_tenant_key
                UNIQUE (tenant_code, setting_key)
        );

        -- Lookup pattern: fetch every setting for a tenant on page load,
        -- or fetch one specific (tenant_code, setting_key) tuple on the
        -- guard hot path. The unique constraint above already covers
        -- both via its btree index.

        COMMENT ON TABLE public.tenant_settings IS
            'Per-tenant plaintext key-value preferences. First consumer: enabledChannels (D2C / B2B / D2C,B2B) gating external API + WMS pull.';
        COMMENT ON COLUMN public.tenant_settings.setting_key IS
            'Property-style key, camelCase. Documented per-consumer in the V73 migration doc + TenantSettingsService.';
        COMMENT ON COLUMN public.tenant_settings.setting_value IS
            'Plaintext value. Encoding per-key; enabledChannels = comma-separated D2C|B2B|D2C,B2B.';
    END IF;
END
$$;
