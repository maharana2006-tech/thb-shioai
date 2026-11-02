-- Manual Shipment redesign, Phase 0: every purchase is an addressable attempt.
-- One row per Idempotency-Key; the durable record that makes a retry safe.
CREATE TABLE shipment_attempt (
    attempt_key        UUID PRIMARY KEY,
    order_no           INTEGER,
    state              VARCHAR(16)  NOT NULL,
    fingerprint        VARCHAR(64)  NOT NULL,
    actor              VARCHAR(200),
    request_json       TEXT         NOT NULL,
    response_json      TEXT,
    carrier_request_id VARCHAR(128),
    error_code         VARCHAR(64),
    error_message      TEXT,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT chk_shipment_attempt_state CHECK (state IN
        ('RESERVED','PURCHASING','DONE','FAILED','PARTIAL','ABANDONED','UNKNOWN'))
);
CREATE INDEX idx_shipment_attempt_order_no ON shipment_attempt (order_no);
CREATE INDEX idx_shipment_attempt_state_updated ON shipment_attempt (state, updated_at);

ALTER TABLE label_batch ADD COLUMN last_attempt_key UUID;

-- created_at on the tracking row is overwritten by regenerate; keep the first one.
ALTER TABLE order_label_tracking ADD COLUMN first_generated_at TIMESTAMP;
UPDATE order_label_tracking SET first_generated_at = COALESCE(label_generated_at, created_at)
 WHERE first_generated_at IS NULL;
