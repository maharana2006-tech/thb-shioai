-- V98 — dtc_orders: surrogate id primary key
-- batch_id was the PK, but one batch spans many totes, so only one row per
-- batch could ever be stored. Uniqueness stays on (batch_id, tote_number,
-- tenant_id) via uk_dtc_orders_batch_tote_tenant (V83).

-- Drop whatever PK exists (name differs between Flyway- and ddl-auto-created tables)
DO $$
DECLARE pk_name TEXT;
BEGIN
    SELECT conname INTO pk_name
      FROM pg_constraint
     WHERE conrelid = 'dtc_orders'::regclass AND contype = 'p';
    IF pk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE dtc_orders DROP CONSTRAINT %I', pk_name);
    END IF;
END $$;

ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS id BIGSERIAL;
ALTER TABLE dtc_orders ADD CONSTRAINT dtc_orders_pkey PRIMARY KEY (id);
ALTER TABLE dtc_orders ALTER COLUMN batch_id SET NOT NULL;
