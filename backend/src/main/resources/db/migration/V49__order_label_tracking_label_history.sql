-- Superseded labels on an order (void / reissue trail). After 'Edit &
-- reissue' the old tracking number simply vanished — nothing on the order
-- recorded that it existed and was voided.
ALTER TABLE order_label_tracking ADD COLUMN IF NOT EXISTS label_history TEXT;
