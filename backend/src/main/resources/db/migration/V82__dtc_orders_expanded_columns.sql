-- V79 — Add expanded columns to dtc_orders for complete Oracle sync
-- Maps all columns from TB_SHIPX_DTC_UVW view to PostgreSQL dtc_orders table
-- Supports new OracleDtcOrder and DtcOrder entity mappings

ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS order_status VARCHAR(10);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS cust_po VARCHAR(100);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS terms_code VARCHAR(10);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS country_name VARCHAR(100);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS ship_via VARCHAR(10);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS tote_number VARCHAR(100);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS track VARCHAR(100);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS freight_cost NUMERIC(13,2);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS ship_date VARCHAR(50);
ALTER TABLE dtc_orders ADD COLUMN IF NOT EXISTS ff_schema_substr VARCHAR(10);

-- Create additional index for customer number (common lookup)
CREATE INDEX IF NOT EXISTS idx_dtc_orders_cust_no ON dtc_orders(cust_no);
