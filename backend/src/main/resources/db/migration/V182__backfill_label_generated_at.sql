-- BUG-079 — labels minted before label_generated_at was stamped left it NULL,
-- so the Carrier Accounts "last used" column had nothing to show. Backfill from
-- the row's own timestamps; new labels are stamped at purchase time.
UPDATE order_label_tracking
   SET label_generated_at = COALESCE(updated_at, created_at)
 WHERE is_label_generated = true
   AND label_generated_at IS NULL
   AND COALESCE(updated_at, created_at) IS NOT NULL;
