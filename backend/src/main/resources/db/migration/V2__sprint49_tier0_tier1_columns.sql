-- Sprint 49 Tier 0 + Tier 1 — new columns and tables.
-- Idempotent so it runs safely alongside Hibernate's ddl-auto=update
-- (Hibernate may have already created these on entity-metadata-driven
-- startup; Flyway just records the version).

-- ---------- Tier 0: webhook signature-bypass fix ----------
ALTER TABLE IF EXISTS carrier_webhook_events
    ADD COLUMN IF NOT EXISTS rejected BOOLEAN DEFAULT FALSE;

-- ---------- Tier 0: admin-managed encrypted secrets ----------
CREATE TABLE IF NOT EXISTS system_settings (
    id              BIGSERIAL PRIMARY KEY,
    setting_key     VARCHAR(200) NOT NULL UNIQUE,
    encrypted_value TEXT,
    updated_at      TIMESTAMP NOT NULL,
    updated_by      VARCHAR(200)
);

-- ---------- Tier 1: webhook replay dedup ----------
ALTER TABLE IF EXISTS carrier_webhook_events
    ADD COLUMN IF NOT EXISTS event_hash VARCHAR(64);
ALTER TABLE IF EXISTS carrier_webhook_events
    ADD COLUMN IF NOT EXISTS duplicate BOOLEAN DEFAULT FALSE;

-- Look up prior events by hash within the dedup window — hot path on every write.
-- Skip if the table hasn't been created yet by Hibernate: CREATE INDEX ... ON
-- <table> errors when the table is missing even though the surrounding ALTER
-- TABLE IF EXISTS statements would have no-op'd. Hibernate ddl-auto=update
-- creates the table + this same index later; Flyway just records the version.
DO $$
BEGIN
    IF to_regclass('carrier_webhook_events') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_carrier_webhook_events_event_hash
            ON carrier_webhook_events (event_hash);
    END IF;
END $$;

-- ---------- Tier 1: encrypt carrier client_secret at rest ----------
-- Widen the column so the base64(nonce||ciphertext||tag) wire format fits.
-- Postgres allows in-place TYPE change for VARCHAR expansion without a rewrite.
ALTER TABLE IF EXISTS carrier_config
    ALTER COLUMN client_secret TYPE VARCHAR(512);
ALTER TABLE IF EXISTS carrier_account_ref
    ALTER COLUMN client_secret TYPE VARCHAR(512);
