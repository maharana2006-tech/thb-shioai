-- V90 — per-connection source / channel gates for writeback dispatch.
--
-- Extends V89's field-flag matrix with FIVE more booleans that gate
-- whether writeback fires AT ALL for a given payload, based on how the
-- label was generated:
--
--   • writeback_source_manual  — /orders/new (ManualShipmentRequest source=MANUAL)
--   • writeback_source_bulk    — bulk CSV import + BulkLabel queue (source=BULK)
--   • writeback_source_api     — external v2 API (source=API)
--   • writeback_channel_d2c    — orders with channel=D2C
--   • writeback_channel_b2b    — orders with channel=B2B
--
-- Semantics: if payload.source is present and the matching column is
-- FALSE, the dispatch is skipped (no field-flag redaction, no connector
-- call). If payload.source is null (e.g. auto path with no origin
-- info), source gating is bypassed. Same for channel. This keeps
-- backwards-compat: existing rows default to TRUE across the board.
--
-- Default TRUE (not FALSE like V89) because these are FILTERS on top of
-- the field-flag matrix — an admin who already enabled field flags on
-- V89 expects them to fire for every flow unless they explicitly
-- narrow. FALSE default would silently break every existing tenant.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_source_manual'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_source_manual BOOLEAN NOT NULL DEFAULT TRUE;
        COMMENT ON COLUMN public.external_system_connection.writeback_source_manual IS
            'V90 — when true, writeback fires for /orders/new manual shipments.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_source_bulk'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_source_bulk BOOLEAN NOT NULL DEFAULT TRUE;
        COMMENT ON COLUMN public.external_system_connection.writeback_source_bulk IS
            'V90 — when true, writeback fires for bulk import + BulkLabel queue shipments.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_source_api'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_source_api BOOLEAN NOT NULL DEFAULT TRUE;
        COMMENT ON COLUMN public.external_system_connection.writeback_source_api IS
            'V90 — when true, writeback fires for external v2 API shipments.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_channel_d2c'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_channel_d2c BOOLEAN NOT NULL DEFAULT TRUE;
        COMMENT ON COLUMN public.external_system_connection.writeback_channel_d2c IS
            'V90 — when true, writeback fires for orders whose channel is D2C.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_channel_b2b'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_channel_b2b BOOLEAN NOT NULL DEFAULT TRUE;
        COMMENT ON COLUMN public.external_system_connection.writeback_channel_b2b IS
            'V90 — when true, writeback fires for orders whose channel is B2B.';
    END IF;
END
$$;
