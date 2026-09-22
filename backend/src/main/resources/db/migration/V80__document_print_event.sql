-- Bulk Mailer: "after print, no deletion" needs to know what was printed.
-- One row per order per print: opened in the print dialog (BROWSER) or sent
-- to a network printer (PRINTER). Opening a preview isn't a print.
CREATE TABLE IF NOT EXISTS document_print_event (
    id           BIGSERIAL PRIMARY KEY,
    order_no     INTEGER      NOT NULL,
    doc_type     VARCHAR(32)  NOT NULL,   -- LABEL · COMMERCIAL_INVOICE
    channel      VARCHAR(16)  NOT NULL,   -- BROWSER · PRINTER
    printer_name VARCHAR(120),
    printed_by   VARCHAR(120),
    printed_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_document_print_event_order ON document_print_event (order_no, printed_at DESC);
