-- D1 — framework-scoped writeback dispatch journal.
--
-- The consolidated audit's biggest ops blind spot: every
-- registry.writeShipment / registry.clearShipment call fires
-- fire-and-forget with a WARN log on failure, then vanishes.
-- There's no persisted record of "did that writeback go through?" and
-- no way to retry from an admin surface.
--
-- V109 adds a per-attempt row. Rows are written by
-- WritebackJournalService, immediately before each dispatcher call.
-- On success/skip/failure the row is updated with the ack + latency +
-- error message. FAILED rows are what /settings/writeback-journal
-- surfaces for manual retry (D1-fe) and what the future sweeper
-- (deferred) would pick up.
--
-- Framework-scoped, not NDS-specific: NDS is the first live connector,
-- REST_JSON stub already exercises the same dispatcher, future SAP /
-- SFTP connectors get the journal for free.

CREATE TABLE external_system_writeback_journal (
    id                 BIGSERIAL PRIMARY KEY,
    connection_name    VARCHAR(120) NOT NULL,
    system_type        VARCHAR(40),
    -- GENERATE | CLEAR. Matches WritebackProbeRequest.mode + the two
    -- dispatcher entry points.
    mode               VARCHAR(16)  NOT NULL,
    -- PENDING (created but not yet acked)
    -- | OK      (registry returned Status.OK)
    -- | SKIPPED (registry returned Status.SKIPPED — flags matrix, empty payload, etc.)
    -- | FAILED  (registry returned Status.FAILED, or an exception was caught)
    status             VARCHAR(16)  NOT NULL,
    client_code        VARCHAR(64),
    order_no           INTEGER,
    tracking_number    VARCHAR(120),
    source             VARCHAR(40),
    channel            VARCHAR(20),
    -- Redacted payload snapshot (secrets never persisted — the
    -- WritebackPayload the connector receives has already been
    -- redacted by the flag matrix). Retry re-runs from this JSON.
    payload_json       TEXT,
    -- WritebackAck.status + WritebackAck.detail. Populated once the
    -- dispatch completes; blank while status='PENDING'.
    ack_status         VARCHAR(16),
    ack_detail         TEXT,
    error_message      TEXT,
    latency_ms         INTEGER,
    -- Retry chain — attempt_number starts at 1; each admin-triggered
    -- retry writes a new row with retry_of_id pointing at the prior
    -- attempt and attempt_number = prev + 1.
    attempt_number     INTEGER      NOT NULL DEFAULT 1,
    retry_of_id        BIGINT REFERENCES external_system_writeback_journal(id) ON DELETE SET NULL,
    -- When set, a scheduled sweeper (deferred) may re-dispatch on/after
    -- this timestamp. Manual retry (D1-fe) ignores this field.
    next_retry_at      TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_writeback_journal_mode   CHECK (mode   IN ('GENERATE','CLEAR')),
    CONSTRAINT ck_writeback_journal_status CHECK (status IN ('PENDING','OK','SKIPPED','FAILED'))
);

CREATE INDEX ix_wbj_created_at      ON external_system_writeback_journal (created_at DESC);
CREATE INDEX ix_wbj_status          ON external_system_writeback_journal (status);
CREATE INDEX ix_wbj_connection      ON external_system_writeback_journal (connection_name);
CREATE INDEX ix_wbj_order_no        ON external_system_writeback_journal (order_no);
CREATE INDEX ix_wbj_client_code     ON external_system_writeback_journal (client_code);
-- Partial index for the sweeper picking due-for-retry rows.
CREATE INDEX ix_wbj_next_retry_at   ON external_system_writeback_journal (next_retry_at)
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;
