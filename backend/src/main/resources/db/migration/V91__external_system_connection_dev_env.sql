-- V91 — per-environment external-system connections.
--
-- Splits the single `external_system_connection` row per connection
-- into (optionally) two rows: one PROD + one DEV. Unique constraint
-- relaxes from `(name)` to `(name, environment)` so both can co-exist.
--
-- A per-connection admin toggle `use_dev` (stored on the PROD row;
-- ignored on the DEV row) tells the resolver which row to hand back
-- when consumer code asks for a connection by name. FALSE by default
-- = PROD stays live for every existing tenant.
--
-- Secrets, client overrides and writeback flags live on the row (via
-- connection_id FK), so a separate DEV row naturally gets its own
-- isolated secret set + client overrides. Tenant → connection routing
-- (tenant_settings.writebackConnection) still resolves by name — the
-- toggle decides which env's row it lands on.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'environment'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN environment VARCHAR(10) NOT NULL DEFAULT 'PROD';
        COMMENT ON COLUMN public.external_system_connection.environment IS
            'V91 — PROD or DEV. Two rows per name allowed via composite unique (name, environment).';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'use_dev'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN use_dev BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.external_system_connection.use_dev IS
            'V91 — canonical on the PROD row: TRUE = resolver hands the DEV row back for this connection name. Ignored on the DEV row itself.';
    END IF;

    -- Swap the unique constraint: (name) -> (name, environment).
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND constraint_name = 'uk_external_system_connection_name'
          AND constraint_type = 'UNIQUE'
    ) THEN
        ALTER TABLE public.external_system_connection
            DROP CONSTRAINT uk_external_system_connection_name;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND constraint_name = 'uk_external_system_connection_name_env'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD CONSTRAINT uk_external_system_connection_name_env
            UNIQUE (name, environment);
    END IF;
END
$$;
