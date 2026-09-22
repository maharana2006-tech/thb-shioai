-- V76 - Internal per-order operator note.
--
-- Free-text ops annotation (driver instructions, pickup hints,
-- handling flags, "call before pickup", etc.). Rendered on
-- /orders/new under the Ship From section (500-char textarea) and
-- surfaced in the orders list as a note-icon on the row with a
-- click-to-expand popover.
--
-- Deliberately INTERNAL only:
--   · Not on external API POST /shipments (external DTOs untouched).
--   · Not printed on labels / commercial invoices (no carrier
--     reference-slot mapping — different concern; see
--     docs/label-po-dept.md for that pattern).
--   · Not in webhook payloads (won't surprise API partners).
--
-- Bound at 500 chars to fit the "driver instructions" use case
-- without letting the column bloat the row payload for the list.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'label_batch'
          AND column_name  = 'note'
    ) THEN
        ALTER TABLE public.label_batch
            ADD COLUMN note VARCHAR(500);
        COMMENT ON COLUMN public.label_batch.note IS
            'V76 — internal per-order ops note (driver instructions / pickup hints / handling flags). 500-char cap. Not on external API / label / webhook.';
    END IF;
END
$$;
