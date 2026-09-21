-- Validate shipment warns when a client's reference / PO has already
-- shipped. The lookup is by client + reference (case-insensitive); without
-- this every Validate click scanned every order. label_batch is partitioned,
-- so this is a plain CREATE INDEX (CONCURRENTLY is not allowed on a
-- partitioned table) — it cascades to each partition.
CREATE INDEX IF NOT EXISTS idx_label_batch_cust_ref
    ON label_batch (upper(cust_no), lower(customer_ref))
    WHERE customer_ref IS NOT NULL;
