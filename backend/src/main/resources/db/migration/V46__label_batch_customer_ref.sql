-- Customer-facing reference on the order: the bulk file's reference /
-- orderRef, or the reference typed on a manual shipment. It was sent to the
-- carrier but never stored, so the Orders grid's Ref # read "—" for every
-- bulk order and the customer's own number could not be traced.
ALTER TABLE label_batch ADD COLUMN IF NOT EXISTS customer_ref VARCHAR(80);
