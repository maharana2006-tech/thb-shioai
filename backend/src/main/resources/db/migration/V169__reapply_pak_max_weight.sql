-- BUG-097 R7 — V160 shipped the 3→5 lb fix for FedEx Pak + UPS Express
-- Pak but QA R7 (2026-10-16) still saw maxWeight=3 on /package-presets.
-- V160 ran once per Flyway history and the data didn't converge (likely a
-- DB rebuild after V160, or a seed that re-wrote the value post-V160).
--
-- V169 re-applies the fix as a separate migration version so it fires
-- against any DB that got stuck pre-fix. Match on the carrier + the exact
-- Pak codes + the name, idempotent via the `< 5` guard.
DO $$
BEGIN
    IF to_regclass('public.carrier_package_catalog') IS NOT NULL THEN
        UPDATE carrier_package_catalog
           SET max_weight = 5
         WHERE carrier_code IN ('FEDEX', 'UPS')
           AND (name ILIKE '%pak%' OR code ILIKE '%pak%' OR code IN ('FEDEX_PAK', '04'))
           AND (max_weight IS NULL OR max_weight < 5);
    END IF;
END $$;
