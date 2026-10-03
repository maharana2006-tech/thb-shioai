-- V126 — Code-Maps/SSM merge, step 1 of 2: add the capabilities SSM had
-- (platform-wide rules + warehouse-scoped routing) to client_shipvia_code_map.
--
-- SSM tables themselves are NOT dropped here — that happens in V128
-- paired with the entity deletes, to avoid Hibernate ddl-auto=update
-- resurrecting them on next boot.
--
-- 1. Relax client_code NOT NULL → nullable (null = platform-wide rule)
-- 2. Add warehouse_id BIGINT NULL (single warehouse scope; add more rows
--    for multi-warehouse. Keeping it a single column avoids a sidecar
--    table for the common case; SSM's list-sidecar earned its rent
--    exactly zero times before this merge).

DO $$
BEGIN
    IF to_regclass('public.client_shipvia_code_map') IS NOT NULL THEN
        ALTER TABLE public.client_shipvia_code_map
            ALTER COLUMN client_code DROP NOT NULL;

        ALTER TABLE public.client_shipvia_code_map
            ADD COLUMN IF NOT EXISTS warehouse_id BIGINT NULL;

        CREATE INDEX IF NOT EXISTS idx_client_shipvia_code_warehouse
            ON public.client_shipvia_code_map (warehouse_id)
            WHERE warehouse_id IS NOT NULL;
    END IF;
END $$;
