-- Sprint 51 backlog BP-L2 — replace the runtime SchemaHealer with a proper
-- migration.
--
-- Before this migration, {@link com.multiship.backend.config.SchemaHealer}
-- ran on every boot and issued `ALTER TABLE ... DROP CONSTRAINT IF EXISTS`
-- to strip five legacy unique keys that were superseded by wider composite
-- keys as the app evolved. Running that at boot is bad hygiene: it hides
-- the drop from the migration history, forces every environment to pay a
-- catalog lookup + tx, and papers over the schema owner (Hibernate vs
-- Flyway) question that Sprint 49 nominally settled.
--
-- Move each drop into a real Flyway step, guarded by the docs pattern
-- (to_regclass on the table + information_schema check on the constraint
-- name) so re-application on a fresh DB or an already-cleaned DB is a
-- no-op. Once this migration ships, SchemaHealer.java is deleted in the
-- same PR — the responsibility now lives in git history where a reviewer
-- can see when + why each constraint went away.

-- ---------- shipping_service.uq_shipping_service_code ----------
-- Used to be (carrier, service_code); superseded by uq_shipping_service_lane
-- on (carrier, service_code, origin_country) so the same service can be
-- offered FROM multiple origins.
DO $$
BEGIN
    IF to_regclass('shipping_service') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_name = 'shipping_service'
               AND constraint_name = 'uq_shipping_service_code') THEN
        ALTER TABLE shipping_service DROP CONSTRAINT uq_shipping_service_code;
    END IF;
END $$;

-- ---------- package_preset.uq_package_preset_name ----------
-- Used to be a single admin-owned catalog uniqued on name; presets are
-- now scoped per (carrier, code, origin) for CARRIER kind and per owner
-- for CUSTOM kind, so multiple rows can legitimately share a name.
DO $$
BEGIN
    IF to_regclass('package_preset') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_name = 'package_preset'
               AND constraint_name = 'uq_package_preset_name') THEN
        ALTER TABLE package_preset DROP CONSTRAINT uq_package_preset_name;
    END IF;
END $$;

-- ---------- client_shipvia_code_map.uq_client_shipvia_code ----------
-- Used to be (client_code, erp_code); aliases now scope by destination
-- (country + region) so the same ERP code can map to different targets
-- per destination.
DO $$
BEGIN
    IF to_regclass('client_shipvia_code_map') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_name = 'client_shipvia_code_map'
               AND constraint_name = 'uq_client_shipvia_code') THEN
        ALTER TABLE client_shipvia_code_map DROP CONSTRAINT uq_client_shipvia_code;
    END IF;
END $$;

-- ---------- client_service_code_map.uq_client_service_code ----------
-- Same rationale as uq_client_shipvia_code — widened by destination.
DO $$
BEGIN
    IF to_regclass('client_service_code_map') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_name = 'client_service_code_map'
               AND constraint_name = 'uq_client_service_code') THEN
        ALTER TABLE client_service_code_map DROP CONSTRAINT uq_client_service_code;
    END IF;
END $$;

-- ---------- client_package_code_map.uq_client_package_code ----------
-- Same rationale as uq_client_shipvia_code — widened by destination.
DO $$
BEGIN
    IF to_regclass('client_package_code_map') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_name = 'client_package_code_map'
               AND constraint_name = 'uq_client_package_code') THEN
        ALTER TABLE client_package_code_map DROP CONSTRAINT uq_client_package_code;
    END IF;
END $$;
