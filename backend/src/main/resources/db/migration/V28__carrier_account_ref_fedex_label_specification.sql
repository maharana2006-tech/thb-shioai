-- FDX-H3 — per-account FedEx label specification (imageType + labelStockType).
--
-- Adds two nullable columns to carrier_account_ref so operators can pick
-- their preferred FedEx label file format and stock size instead of the
-- hardcoded PDF / PAPER_4X6 sent on every FedEx createShipment call.
--
-- Value semantics (FedEx labelSpecification enums; other carriers no-op):
--   fedex_label_image_type  — imageType: PDF | PNG | ZPLII | EPL2 | DPL
--     NULL → resolver falls back to hardcoded "PDF" default.
--   fedex_label_stock_type  — labelStockType: PAPER_4X6 | PAPER_4X6.75 |
--     PAPER_4X8 | PAPER_4X9 | PAPER_7X4.75 | PAPER_LETTER | STOCK_4X6 |
--     STOCK_4X6.75 | STOCK_4X8 | STOCK_4X9_LEADING_DOC_TAB | ...
--     NULL → resolver falls back to hardcoded "PAPER_4X6" default.
--
-- Deliberately NO backfill: existing FedEx accounts keep getting PDF /
-- PAPER_4X6 exactly as before. Operators opt in per account via the
-- CarrierConnections drawer.
--
-- Fresh-DB pattern: guarded by to_regclass so a fresh Postgres skips this
-- migration and Hibernate materialises the columns from the @Entity on
-- next boot. See docs/flyway-fresh-db-guard-pattern.md.
DO $$
BEGIN
    IF to_regclass('public.carrier_account_ref') IS NOT NULL THEN
        ALTER TABLE carrier_account_ref
            ADD COLUMN IF NOT EXISTS fedex_label_image_type VARCHAR(10),
            ADD COLUMN IF NOT EXISTS fedex_label_stock_type VARCHAR(30);
    END IF;
END $$;
