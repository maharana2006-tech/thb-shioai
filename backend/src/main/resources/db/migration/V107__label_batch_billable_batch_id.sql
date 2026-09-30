-- B7 — persist the NDS billable batch id on the label_batch (Order) row.
--
-- Populated by CarrierServiceImpl.generateManualLabel when the request
-- came from a `.Y<batchId>` scan (B6 threading). Enables:
--   1. Void fan-out — a future PR marks every sibling order's tracking
--      as VOIDED when any label in the batch is cancelled.
--   2. Admin surface — /orders list can show "batch: B42, 5 orders"
--      by grouping on this column.
--
-- Nullable + no default; existing rows keep their null and only new
-- shipments from `.Y` scans populate it.

ALTER TABLE label_batch
    ADD COLUMN billable_batch_id VARCHAR(50);

CREATE INDEX ix_label_batch_billable_batch_id
    ON label_batch (billable_batch_id)
    WHERE billable_batch_id IS NOT NULL;
