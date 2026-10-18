-- A label that was closed out (manifest / SCAN form / end-of-day) is no longer
-- "open": it must not be offered for, or filed in, a second close-out.
ALTER TABLE order_label_tracking ADD COLUMN IF NOT EXISTS manifested_at TIMESTAMP;
ALTER TABLE order_label_tracking ADD COLUMN IF NOT EXISTS manifest_id  VARCHAR(120);
