-- UPS-4a (UPS-audit follow-up) — per-account UPS LabelImageFormat.
--
-- Adds a nullable label_image_format column to carrier_account_ref so
-- operators can pick their preferred UPS label format instead of the
-- pre-fix hardcode of "GIF" on every UPS label. Pre-fix, shippers with
-- high-quality label printers got fuzzy rasterised GIF labels regardless
-- of preference.
--
-- Value semantics (UPS LabelImageFormat enum; other carriers no-op):
--   GIF  — default (matches pre-UPS-4a hardcode); smallest wire size,
--          rasterises fuzzy on ZPL printers
--   PDF  — vector; sharp on any printer; slightly larger wire size
--   PNG  — raster; similar quality to GIF
--   ZPL  — Zebra printer language; text-only (no image decode needed)
--   EPL  — Eltron printer language; legacy Zebra format
--
-- Semantics of NULL:
--   NULL → resolver falls back to hardcoded "GIF" default (matches
--          pre-UPS-4a behavior). Existing rows read exactly as before.
--
-- Deliberately NO backfill: silently flipping every existing UPS account
-- to a new format would break operators who tuned their label-printing
-- pipeline around GIF. Operators set the column explicitly per account
-- via the CarrierConnections drawer.
--
-- Fresh-DB pattern: guarded by to_regclass so a fresh Postgres skips
-- this migration and Hibernate materialises the column from the @Entity
-- on next boot. See docs/flyway-fresh-db-guard-pattern.md.
--
-- No code path reads this column until UPS-4b (resolver + connector)
-- lands, so this migration is zero-risk on its own.
DO $$
BEGIN
    IF to_regclass('public.carrier_account_ref') IS NOT NULL THEN
        ALTER TABLE carrier_account_ref
            ADD COLUMN IF NOT EXISTS label_image_format VARCHAR(10);
    END IF;
END $$;
