-- §4 extraction — alert_history (Jobs implicit).
--
-- UspsFallbackAlertService holds a bounded (50-entry) in-memory
-- ArrayDeque that goes dark on every restart. Ops want to answer "how
-- many silent fallbacks last month?" and today the only durable record
-- is log lines in whatever log aggregator the install uses (often none).
--
-- V113 adds a thin alert_history table. UspsFallbackAlertService.record
-- still writes the in-memory ring (zero-latency dashboard reads) AND
-- persists one durable row. Older alerts age out of the ring but stay
-- in the table for admin history + reporting.

DO $$
BEGIN
    IF to_regclass('public.alert_history') IS NULL THEN
        CREATE TABLE alert_history (
            id              BIGSERIAL PRIMARY KEY,
            fired_at        TIMESTAMP NOT NULL DEFAULT NOW(),
            source          VARCHAR(80) NOT NULL,
            template_key    VARCHAR(120),
            target_order_no BIGINT,
            tenant_code     VARCHAR(64),
            import_batch_id BIGINT,
            reason          TEXT,
            payload_json    TEXT
        );
        CREATE INDEX IF NOT EXISTS ix_alert_history_fired_at
            ON alert_history (fired_at DESC);
        CREATE INDEX IF NOT EXISTS ix_alert_history_source
            ON alert_history (source);
        CREATE INDEX IF NOT EXISTS ix_alert_history_tenant_code
            ON alert_history (tenant_code);
        CREATE INDEX IF NOT EXISTS ix_alert_history_target_order_no
            ON alert_history (target_order_no);
    END IF;
END $$;
