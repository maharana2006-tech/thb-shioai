-- V102 — DTC batch label generation (D2C History parity with ShipX /DTC/Dtcal)
-- Per-row generation outcome stamped by the background worker so the batch
-- summary + line pages can show Label/Status without joining order_label_tracking.
-- generated_order_no links the DTC row to the minted Order (order_no) so
-- Print/Void reuse the existing /orders/{orderNo}/label + /orders/{orderNo}/void endpoints.

ALTER TABLE dtc_orders
    ADD COLUMN IF NOT EXISTS generated_order_no INTEGER,
    ADD COLUMN IF NOT EXISTS generated_tracking_number VARCHAR(64),
    ADD COLUMN IF NOT EXISTS generated_carrier_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS generated_status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS generated_message VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS generated_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_dtc_orders_gen_status ON dtc_orders (tenant_id, batch_id, generated_status);
CREATE INDEX IF NOT EXISTS idx_dtc_orders_gen_order_no ON dtc_orders (generated_order_no);

-- One row per "Automatic label" click on a DTC batch. The worker claims
-- QUEUED rows (SELECT FOR UPDATE SKIP LOCKED), stamps RUNNING + heartbeat,
-- and executes DtcLabelGenerationService per row. Mirrors import_generation_job.
CREATE TABLE IF NOT EXISTS dtc_generation_job (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    batch_id NUMERIC(38,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    total_rows INTEGER NOT NULL DEFAULT 0,
    processed_rows INTEGER NOT NULL DEFAULT 0,
    generated_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    error_message VARCHAR(2000),
    requested_by VARCHAR(100),
    worker_id VARCHAR(64),
    queued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    heartbeat_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dtc_gen_job_claim ON dtc_generation_job (status, queued_at);
CREATE INDEX IF NOT EXISTS idx_dtc_gen_job_batch ON dtc_generation_job (tenant_id, batch_id);
