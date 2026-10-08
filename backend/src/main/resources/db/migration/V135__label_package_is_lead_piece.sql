-- PR-T9-X1 (audit T-MPS3) — lead-piece flag on label_package.
--
-- The customer-facing UI needs ONE tracking number per shipment to
-- display (even when the shipment is MPS). SERA / SWSIM / most USPS
-- carriers return per-piece tracking numbers with no notion of a
-- shipment-level master, so we designate piece 1 as the lead by
-- convention. This flag makes that convention explicit so later
-- queries can `WHERE is_lead_piece = TRUE` without an index-less
-- MIN(sequence_number) GROUP BY.
--
-- Backfill: every existing shipment's piece with the minimum
-- sequence_number for its order becomes the lead. For single-piece
-- shipments that's trivially piece 1; for MPS it's piece 1 by
-- convention since CarrierServiceImpl writes sequence_number
-- monotonically.
--
-- Fresh-DB-guard pattern: wrapped in a to_regclass check so a cold
-- bootstrap that reaches V135 before label_package exists silently
-- no-ops. See docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('public.label_package') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'label_package'
              AND column_name = 'is_lead_piece'
       ) THEN
        ALTER TABLE label_package
            ADD COLUMN is_lead_piece BOOLEAN NOT NULL DEFAULT FALSE;

        -- Backfill: mark the lowest sequence_number per order as lead.
        UPDATE label_package lp
           SET is_lead_piece = TRUE
         WHERE (lp.order_no, lp.sequence_number) IN (
                   SELECT order_no, MIN(sequence_number)
                     FROM label_package
                    GROUP BY order_no);

        -- Partial index — the lookup is always "find the lead for this
        -- order", so index only is_lead_piece=TRUE rows. Keeps the
        -- index tiny (~1/N of the table for an N-piece MPS average).
        CREATE INDEX IF NOT EXISTS ix_label_package_lead
            ON label_package (order_no)
         WHERE is_lead_piece = TRUE;

        RAISE NOTICE 'V135 added label_package.is_lead_piece + backfilled lead-piece convention';
    END IF;
END $$;
