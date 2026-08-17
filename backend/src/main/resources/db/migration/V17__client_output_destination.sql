-- Sprint 52 (per-client output routing + network printing).
--
-- Two related tables:
--
-- 1) client_output_destination — the routing config. Each row says
--    "for client X, when we generate doc-type Y, dispatch to destination Z".
--    Destinations are LOCAL_FS (write bytes to a directory on the box),
--    SFTP (upload bytes to a customer sftp drop), or PRINTER (spool to a
--    network printer, RAW_9100 for Zebra ZPL or IPP for laser PDF).
--
--    The heterogeneous per-type config lives in a JSONB column so we don't
--    grow the schema every time someone asks for a new option. Passwords /
--    private-key material never sit in JSONB in plaintext — those fields
--    hold a *secret id* that references an encrypted blob via the existing
--    AES-GCM secret converter (Sprint 49 Tier 0 CryptoService). The admin
--    controller enforces "write-only from UI" — reads never return the
--    encrypted value.
--
-- 2) shipment_document — the always-on DB copy. Every generated label / CI
--    lands here as raw bytes, keyed by (shipment_id, doc_type). Normalized
--    (instead of appending BYTEA columns to shipment) so a 3PL that puts
--    a 500KB label on every one of 1M shipments in a year gets 500GB of
--    label data on its own table with its own TOAST heap — the shipment
--    row scans stay cheap and the CI/label bytes don't bloat every
--    SELECT * from shipment.
--
-- Follows the fresh-DB Flyway pattern (docs/flyway-fresh-db-guard-pattern.md):
-- both blocks guarded by to_regclass so a fresh Postgres with no table yet
-- skips cleanly and lets Hibernate materialise the tables on the next boot.
DO $$
BEGIN
    IF to_regclass('public.client_output_destination') IS NULL THEN
        CREATE TABLE client_output_destination (
            id                BIGSERIAL PRIMARY KEY,
            client_code       VARCHAR(64)  NOT NULL,
            doc_type          VARCHAR(32)  NOT NULL,
            destination_type  VARCHAR(32)  NOT NULL,
            config            JSONB        NOT NULL,
            active            BOOLEAN      NOT NULL DEFAULT TRUE,
            notes             VARCHAR(500),
            created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
            updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
            CONSTRAINT chk_cod_doc_type
                CHECK (doc_type IN ('LABEL', 'COMMERCIAL_INVOICE')),
            CONSTRAINT chk_cod_destination_type
                CHECK (destination_type IN ('LOCAL_FS', 'SFTP', 'PRINTER'))
        );

        -- Lookup index for the dispatch hot path: given (clientCode, docType)
        -- find every active destination. Very short list (typically 1-3 rows
        -- per client per doc-type) so a plain btree covers it.
        CREATE INDEX idx_cod_lookup
            ON client_output_destination (client_code, doc_type, active);
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.shipment_document') IS NULL THEN
        CREATE TABLE shipment_document (
            id            BIGSERIAL PRIMARY KEY,
            shipment_id   BIGINT       NOT NULL,
            order_no      INT          NULL,
            client_code   VARCHAR(64)  NULL,
            doc_type      VARCHAR(32)  NOT NULL,
            content_type  VARCHAR(64)  NULL,
            bytes         BYTEA        NOT NULL,
            byte_size     INT          NOT NULL,
            created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
            CONSTRAINT chk_shipdoc_doc_type
                CHECK (doc_type IN ('LABEL', 'COMMERCIAL_INVOICE'))
        );

        -- Fetch every doc for a shipment (typical: 1 label, 0 or 1 CI).
        CREATE INDEX idx_shipdoc_shipment ON shipment_document (shipment_id, doc_type);
        -- Optional lookup by orderNo — some flows carry order but no shipment
        -- row (single-shipment legacy path); allow retrieval by orderNo too.
        CREATE INDEX idx_shipdoc_order ON shipment_document (order_no);
    END IF;
END $$;
