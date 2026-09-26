-- V89 — per-connection writeback flags.
--
-- Adds six BOOLEAN columns to external_system_connection so an admin
-- can toggle, per-connection AND per-payload-field, which shipment
-- details are pushed BACK to the external system when a label is
-- generated (and cleared on void). The same six flags gate BOTH sides:
-- if writeback_carrier is off, the carrier code neither goes out on
-- generate nor gets nulled on void.
--
-- Off-by-default (`DEFAULT FALSE`) so upgrading an existing DB does
-- not start pushing to any live external system until an admin
-- explicitly turns the flags on from /settings/external-systems.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md — every
-- column add is guarded by information_schema.columns.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_tracking'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_tracking BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.external_system_connection.writeback_tracking IS
            'V89 — when true, push carrier tracking number to this system on label generate and NULL it on void.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_ship_date'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_ship_date BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.external_system_connection.writeback_ship_date IS
            'V89 — when true, push shipment date (labelGeneratedAt) on generate and NULL it on void.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_status'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_status BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.external_system_connection.writeback_status IS
            'V89 — when true, push shipment status (SHIPPED on generate / VOIDED on clear).';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_carrier'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_carrier BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.external_system_connection.writeback_carrier IS
            'V89 — when true, push carrier code (UPS / FEDEX / …) on generate and NULL it on void.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_service'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_service BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.external_system_connection.writeback_service IS
            'V89 — when true, push carrier service code (FEDEX_GROUND, UPS_02, …) on generate and NULL it on void.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_freight'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_freight BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.external_system_connection.writeback_freight IS
            'V89 — when true, push freight amount + currency on generate and NULL them on void.';
    END IF;
END
$$;
