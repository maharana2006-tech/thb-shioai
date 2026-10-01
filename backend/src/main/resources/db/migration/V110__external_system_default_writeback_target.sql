-- X1 — retire the compile-time DEFAULT_CONNECTION_NAME = "nds-default"
-- literal in ExternalSystemWritebackDispatcher. Prior to this, a tenant
-- without a tenant_settings.writebackConnection override and an install
-- whose default connection isn't named "nds-default" silently wrote to
-- the wrong server (ext-sys X1 finding).
--
-- One row per {name, environment} may be marked as the default
-- writeback target. The partial unique index enforces at most one TRUE
-- row at any time; admin UI (/settings/external-systems) toggles it,
-- and ExternalSystemWritebackDispatcher.resolveConnectionName picks it
-- up by column instead of by name.
--
-- Backfill: the existing nds-default row (if present) keeps its
-- behaviour. Installs that don't have one get no default, which is the
-- correct "silent skip" behaviour per the dispatcher javadoc.

DO $$
BEGIN
    IF to_regclass('public.external_system_connection') IS NOT NULL THEN
        ALTER TABLE external_system_connection
            ADD COLUMN IF NOT EXISTS is_default_writeback_target BOOLEAN NOT NULL DEFAULT FALSE;

        CREATE UNIQUE INDEX IF NOT EXISTS uq_external_system_default_writeback_target
            ON external_system_connection (is_default_writeback_target)
            WHERE is_default_writeback_target = TRUE;

        -- Preserve prior behaviour: the hardcoded "nds-default" is still
        -- the default if it exists. PROD env wins when both PROD + DEV
        -- rows share the name (DEV routing happens via use_dev on PROD).
        UPDATE external_system_connection
            SET is_default_writeback_target = TRUE
            WHERE name = 'nds-default'
              AND (environment IS NULL OR environment = 'PROD')
              AND NOT EXISTS (
                  SELECT 1 FROM external_system_connection
                  WHERE is_default_writeback_target = TRUE
              );
    END IF;
END $$;
