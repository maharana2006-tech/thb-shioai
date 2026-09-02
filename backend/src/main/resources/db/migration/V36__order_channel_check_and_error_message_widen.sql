-- Two data-integrity fixes from the D2C/B2B verification run.
--
-- 1. order_channel had no enum enforcement: the manual-label path accepted
--    any string (e.g. "DTC") and persisted it verbatim, where the UI then
--    rendered it identically to an unclassified row — invisible bad data.
--    Normalize what's already there (recompute from the recipient company,
--    the same heuristic the persist path uses), then add a CHECK so no
--    future writer — app bug or hand-run SQL — can reintroduce it.
--
-- 2. order_label_tracking.error_message is still varchar(255) on databases
--    where V31's fresh-DB guard skipped (Hibernate created the table AFTER
--    Flyway ran, with the JPA default). Long FedEx rejections then blew up
--    the failed-order persist itself: the operator was told "saved as order
--    N" while the REQUIRES_NEW insert rolled back on "value too long" and
--    the order vanished. Re-widen unconditionally.

UPDATE label_batch
SET order_channel = CASE
        WHEN ship_attn IS NOT NULL AND btrim(ship_attn) <> '' THEN 'B2B'
        ELSE 'D2C'
    END
WHERE order_channel IS NOT NULL
  AND order_channel NOT IN ('D2C', 'B2B');

ALTER TABLE label_batch DROP CONSTRAINT IF EXISTS label_batch_order_channel_check;
ALTER TABLE label_batch ADD CONSTRAINT label_batch_order_channel_check
    CHECK (order_channel IS NULL OR order_channel IN ('D2C', 'B2B'));

DO $$
BEGIN
    IF to_regclass('public.order_label_tracking') IS NULL THEN
        RAISE NOTICE 'V36 error_message widen skipped — order_label_tracking missing (fresh DB; entity mapping creates it as text)';
        RETURN;
    END IF;
    ALTER TABLE order_label_tracking ALTER COLUMN error_message TYPE TEXT;
END $$;
