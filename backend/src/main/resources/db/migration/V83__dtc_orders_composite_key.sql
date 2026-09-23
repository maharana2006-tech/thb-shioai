-- V80 — Add unique constraint on dtc_orders composite key
-- Ensures uniqueness of (batch_id, tote_number, tenant_id) combination
-- Prevents duplicate orders when same batch exists for different totes/tenants

-- Add unique index on composite key
CREATE UNIQUE INDEX IF NOT EXISTS uk_dtc_orders_batch_tote_tenant 
ON dtc_orders(batch_id, tote_number, tenant_id);

-- Add comment explaining the composite key
COMMENT ON INDEX uk_dtc_orders_batch_tote_tenant IS 
'Unique constraint on (batch_id, tote_number, tenant_id) composite key. 
A batch_id can exist for multiple totes or tenants, so the combination of all three ensures uniqueness.';
