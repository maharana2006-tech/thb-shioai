-- D2C / B2B channel classification, requested for the API orders section.
-- Computed once at persist time (explicit API `channel` field wins, then
-- recipient residential flag, then recipient company presence) and stored,
-- so the list query can filter/export without re-deriving. Nullable on
-- purpose: legacy rows predate the signal and render as "—" rather than
-- guessing. Parent-table ALTER propagates to the LIST partitions.

ALTER TABLE label_batch ADD COLUMN IF NOT EXISTS order_channel varchar(3);
