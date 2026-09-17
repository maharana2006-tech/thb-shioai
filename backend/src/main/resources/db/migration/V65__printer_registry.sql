-- Printer management + per-client printer routing.
--
-- Printers are registered once (connection, format, paper) and ASSIGNED to
-- clients per document type, instead of repeating host/port inside every
-- routing row. A row with client_code NULL is the default printer for that
-- document type, used by clients that have no printer of their own.
CREATE TABLE IF NOT EXISTS printer (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(120) NOT NULL,
    location          VARCHAR(160),
    -- RAW_9100: bytes straight to the printer's port (ZPL thermal printers,
    --           PDF-capable lasers). IPP: an IPP Print-Job request over HTTP.
    connection        VARCHAR(20)  NOT NULL,
    host              VARCHAR(255) NOT NULL,
    port              INT          NOT NULL,
    queue_path        VARCHAR(160),
    -- What the printer understands: ZPL (thermal labels) or PDF.
    format            VARCHAR(10)  NOT NULL,
    -- LABEL_4X6 | A4 | LETTER
    paper             VARCHAR(20)  NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    last_test_at      TIMESTAMP,
    last_test_ok      BOOLEAN,
    last_test_message VARCHAR(500),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS printer_assignment (
    id          BIGSERIAL PRIMARY KEY,
    -- NULL = default for every client without its own assignment
    client_code VARCHAR(60),
    -- LABEL | COMMERCIAL_INVOICE
    doc_type    VARCHAR(30) NOT NULL,
    printer_id  BIGINT      NOT NULL REFERENCES printer (id) ON DELETE CASCADE,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT now()
);

-- One printer per (client, document type); one default per document type.
CREATE UNIQUE INDEX IF NOT EXISTS uq_printer_assignment_client_doc
    ON printer_assignment (COALESCE(UPPER(client_code), ''), doc_type);
