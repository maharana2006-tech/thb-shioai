-- §4 extraction — carrier_api_log (Jobs implicit).
--
-- shioai has no durable carrier API log. ShipX had SPX_Shipment_API_Log
-- with 2k+ rows per month used for post-mortems ("what did we actually
-- send FedEx on this order?"). Today the only trace is log lines.
--
-- V114 adds the table + writer-side infrastructure; connector wiring
-- happens as consumers are touched (opt-in via CarrierApiLogService
-- call from any connector method that wants to persist a round-trip).
-- First batch of wiring deliberately deferred — infrastructure first,
-- behaviour swaps as follow-ups.

DO $$
BEGIN
    IF to_regclass('public.carrier_api_log') IS NULL THEN
        CREATE TABLE carrier_api_log (
            id             BIGSERIAL PRIMARY KEY,
            request_id     VARCHAR(80),
            carrier        VARCHAR(32) NOT NULL,
            method         VARCHAR(8) NOT NULL,
            url            TEXT NOT NULL,
            request_body   TEXT,
            response_body  TEXT,
            status_code    INTEGER,
            latency_ms     INTEGER,
            error_message  TEXT,
            order_no       BIGINT,
            tracking       VARCHAR(120),
            created_at     TIMESTAMP NOT NULL DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS ix_carrier_api_log_created_at
            ON carrier_api_log (created_at DESC);
        CREATE INDEX IF NOT EXISTS ix_carrier_api_log_carrier
            ON carrier_api_log (carrier);
        CREATE INDEX IF NOT EXISTS ix_carrier_api_log_order_no
            ON carrier_api_log (order_no);
        CREATE INDEX IF NOT EXISTS ix_carrier_api_log_tracking
            ON carrier_api_log (tracking);
        CREATE INDEX IF NOT EXISTS ix_carrier_api_log_request_id
            ON carrier_api_log (request_id);
    END IF;
END $$;
