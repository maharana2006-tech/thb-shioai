-- Who the carrier was told to bill duties & taxes to, and the payer's
-- account for third-party billing. The New Shipment form always offered
-- the choice but the request dropped it, so nothing recorded what the
-- carrier was actually asked to do; the invoice printed the Incoterm's
-- default instead of the real payer.
ALTER TABLE order_customs ADD COLUMN IF NOT EXISTS duties_paid_by VARCHAR(12);
ALTER TABLE order_customs ADD COLUMN IF NOT EXISTS duties_account VARCHAR(60);
