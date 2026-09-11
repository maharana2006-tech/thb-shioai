-- Bulk upload restructure (2026-09-11): an uploaded CSV / XLSX is parked in a
-- STAGING area and validated there. Nothing reaches Import history
-- (import_batch) until the operator clicks Save, and Save writes only the
-- orders whose every row is valid. Orders with errors stay staged (editable,
-- downloadable as an error file) until fixed, discarded, or expired.
CREATE TABLE IF NOT EXISTS import_staging_upload (
    id                  BIGSERIAL    PRIMARY KEY,
    created_by          VARCHAR(120),
    file_name           VARCHAR(260),
    content_hash        VARCHAR(128),
    status              VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    total_rows          INTEGER      NOT NULL DEFAULT 0,
    valid_rows          INTEGER      NOT NULL DEFAULT 0,
    invalid_rows        INTEGER      NOT NULL DEFAULT 0,
    total_orders        INTEGER      NOT NULL DEFAULT 0,
    valid_orders        INTEGER      NOT NULL DEFAULT 0,
    saved_orders        INTEGER      NOT NULL DEFAULT 0,
    last_saved_batch_id BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_import_staging_upload_created_at ON import_staging_upload (created_at);
CREATE INDEX IF NOT EXISTS idx_import_staging_upload_hash ON import_staging_upload (content_hash, status);

CREATE TABLE IF NOT EXISTS import_staging_row (
    id             BIGSERIAL    PRIMARY KEY,
    upload_id      BIGINT       NOT NULL REFERENCES import_staging_upload (id) ON DELETE CASCADE,
    row_no         INTEGER      NOT NULL,
    order_ref      VARCHAR(120),
    valid          BOOLEAN      NOT NULL DEFAULT FALSE,
    saved_batch_id BIGINT,
    row_json       TEXT         NOT NULL,
    CONSTRAINT uk_import_staging_row UNIQUE (upload_id, row_no)
);
CREATE INDEX IF NOT EXISTS idx_import_staging_row_upload ON import_staging_row (upload_id);
