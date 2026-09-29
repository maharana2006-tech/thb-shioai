-- V93 — per-connection writeback source gates for DTC + WMS.
--
-- Extends V90's source gate matrix (MANUAL / BULK / API) with two more
-- flags for the fetch-driven flows:
--
--   • writeback_source_wms — Fetch from WMS on /orders/api-batches
--     (WmsService.pullShippable creates ImportBatch source=WMS; the
--     generate-labels path now stamps ManualShipmentRequest.source=WMS).
--   • writeback_source_dtc — Fetch from NDS on /d2c/history
--     (DtcService.pullShippable creates ImportBatch source=DTC).
--
-- Same semantics as V90: TRUE = fire; FALSE = skip; unknown values on
-- the payload's source string pass through the dispatcher gate.
--
-- Default TRUE (matches V90's default) so existing tenants keep
-- receiving writeback across all fetch surfaces after upgrade.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_source_wms'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_source_wms BOOLEAN NOT NULL DEFAULT TRUE;
        COMMENT ON COLUMN public.external_system_connection.writeback_source_wms IS
            'V93 — when true, writeback fires for WMS-fetched batches (Fetch from WMS on /orders/api-batches).';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'external_system_connection'
          AND column_name = 'writeback_source_dtc'
    ) THEN
        ALTER TABLE public.external_system_connection
            ADD COLUMN writeback_source_dtc BOOLEAN NOT NULL DEFAULT TRUE;
        COMMENT ON COLUMN public.external_system_connection.writeback_source_dtc IS
            'V93 — when true, writeback fires for D2C History Fetch from NDS shipments.';
    END IF;
END
$$;
