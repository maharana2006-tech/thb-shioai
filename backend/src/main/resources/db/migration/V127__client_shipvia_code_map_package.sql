-- V127 — Code-Maps/SSM merge, step 2 of 2: packaging allowlist sidecar.
--
-- Mirrors the pre-merge ship_method_rule_package sidecar: one row per
-- (map_id, preset_id). Empty = unrestricted. The row-FK handles cascade
-- cleanup when a map row is deleted. Composite PK keeps duplicates out.

DO $$
BEGIN
    IF to_regclass('public.client_shipvia_code_map') IS NOT NULL
       AND to_regclass('public.client_shipvia_code_map_package') IS NULL THEN
        CREATE TABLE public.client_shipvia_code_map_package (
            map_id    BIGINT NOT NULL
                REFERENCES public.client_shipvia_code_map(id) ON DELETE CASCADE,
            preset_id BIGINT NOT NULL,
            PRIMARY KEY (map_id, preset_id)
        );

        CREATE INDEX IF NOT EXISTS idx_client_shipvia_code_map_package_preset
            ON public.client_shipvia_code_map_package (preset_id);
    END IF;
END $$;
