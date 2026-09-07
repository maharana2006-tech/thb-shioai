-- Commercial-invoice paper size per client. A4 (default) covers the
-- ISO standard used everywhere except US/Canada/Mexico; tenants
-- shipping from those markets can flip to LETTER via the Settings
-- UI. Persisted on Client (not per-order) because a shipper prints
-- consistent paperwork across all their orders.
--
-- Renderer resolution:
--   client.defaultPaperSize non-null → use that
--   client is anonymous (MANUAL orders) → A4 fallback
--   any invalid value → A4 fallback (defensive)
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: ADD COLUMN IF
-- NOT EXISTS.
DO $$
BEGIN
    IF to_regclass('public.client') IS NULL THEN
        RAISE NOTICE 'V43 skipped — client table missing (fresh DB before first sync)';
        RETURN;
    END IF;
    ALTER TABLE client
        ADD COLUMN IF NOT EXISTS default_paper_size VARCHAR(8);
    RAISE NOTICE 'V43 added client.default_paper_size (A4 default at render time)';
END $$;
