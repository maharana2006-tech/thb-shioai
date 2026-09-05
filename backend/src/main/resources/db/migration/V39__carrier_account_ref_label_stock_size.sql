-- Per-account label stock size. UPS Ship API v1 requires
-- LabelSpecification.LabelStockSize.Height + .Width in inches (rejected
-- with 9120244 "Missing label specification label stock size." when
-- absent). PR #578 hardcoded 6x4; this migration lets operators pick
-- per-account (4x6 default matches the previous hardcode).
--
-- Applies to ALL 4 carriers with per-connector normalisation:
--   UPS   → LabelStockSize {Height, Width} in inches
--   FedEx → labelStockType enum ("STOCK_4X6" / "STOCK_4X8" / "STOCK_4X9_LEADING_DOC_TAB")
--   DHL   → labelSpecification format (encoded per DHL Express spec)
--   Stamps → SWSIM PrintLayout
--
-- Both columns nullable — nulls fall to the ShipmentDefaultsResolver's
-- 4x6 default so pre-existing accounts don't need a data backfill.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: ADD COLUMN IF NOT
-- EXISTS handles re-runs. See docs/flyway-fresh-db-guard-pattern.md.
DO $$
BEGIN
    IF to_regclass('public.carrier_account_ref') IS NULL THEN
        RAISE NOTICE 'V39 skipped — carrier_account_ref missing (fresh DB before first sync)';
        RETURN;
    END IF;

    ALTER TABLE carrier_account_ref
        ADD COLUMN IF NOT EXISTS label_stock_height NUMERIC(3,1),
        ADD COLUMN IF NOT EXISTS label_stock_width  NUMERIC(3,1);

    RAISE NOTICE 'V39 added carrier_account_ref.label_stock_height + label_stock_width';
END $$;
