-- BUG-121 — tenant_settings held rows for MKL274, a client code that
-- never existed in the clients table. Delete orphan rows so Routed
-- clients lists only live clients and the writeback dispatcher doesn't
-- try to resolve a non-existent tenant.
--
-- Idempotent: NOT EXISTS filter is empty after first apply.
DO $$
BEGIN
    IF to_regclass('public.tenant_settings') IS NOT NULL
       AND to_regclass('public.clients') IS NOT NULL THEN
        DELETE FROM tenant_settings ts
         WHERE NOT EXISTS (
             SELECT 1 FROM clients c
              WHERE UPPER(c.client_code) = UPPER(ts.tenant_code));
    END IF;
END $$;
