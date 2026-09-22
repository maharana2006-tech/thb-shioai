-- V78 — seed the "nds-default" external_system_connection row.
--
-- Rows before this migration: none (framework tables landed in V77
-- empty by design). This migration seeds ONE placeholder connection
-- so:
--   1. The admin visiting /settings/external-systems after upgrade
--      sees a pre-created row to configure rather than an empty
--      table with no obvious next step.
--   2. Consumers of ExternalSystemRegistry can start hardcoding the
--      well-known name "nds-default" without racing against admin
--      setup.
--
-- Seed shape:
--   - active = FALSE — admin MUST configure secrets + edit config
--     before the row goes live. Force-picking default: no accidental
--     boot-time attempt to reach Oracle with placeholder values.
--   - config_json holds the confirmed server descriptor for NDS
--     (host / port / serviceName / serverMode / productionUsername).
--     The admin can edit these via the FE if they change.
--   - NO secret is inserted — external_system_secret stays empty
--     until the admin sets productionPassword via the UI. Missing
--     secret = connector throws SECRET_UNAVAILABLE at first use,
--     which the UI surfaces cleanly.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.external_system_connection WHERE name = 'nds-default'
    ) THEN
        INSERT INTO public.external_system_connection
            (name, system_type, active, config_json, created_at, updated_at, updated_by)
        VALUES (
            'nds-default',
            'NDS_ORACLE',
            FALSE,
            '{"host":"192.168.3.8","port":1521,"serviceName":"tb10g","serverMode":"DEDICATED","productionUsername":"production","clientCodePattern":"^[A-Z0-9-]{3,12}$","poolMaxSize":5,"poolMinIdle":1,"poolConnectionTimeoutMs":10000,"poolIdleTimeoutMs":600000,"poolMaxLifetimeMs":1800000,"clientPoolEvictMinutes":30}',
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP,
            'V78 seed'
        );
    END IF;
END
$$;
