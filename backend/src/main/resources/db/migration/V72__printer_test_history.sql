-- V72 - Printer R9.5a: per-attempt test-print log.
--
-- The Printer row's lastTestAt/lastTestOk/lastTestMessage fields only
-- record the LATEST test attempt. This table captures every attempt
-- so the FE PrinterDetailsPanel can show a rolling history for
-- diagnosis when a printer flakes intermittently.
--
-- Cascade delete: dropping a printer drops its test history.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('public.printer_test_history') IS NULL THEN
        CREATE TABLE public.printer_test_history (
            id          BIGSERIAL PRIMARY KEY,
            printer_id  BIGINT       NOT NULL,
            tested_at   TIMESTAMP    NOT NULL,
            ok          BOOLEAN      NOT NULL,
            -- Same TEXT column type as OrderTracking.error_message —
            -- carrier / IPP / raw-9100 error responses can be long.
            message     TEXT,
            -- Who clicked the Test button (JWT username). Nullable
            -- because scheduled / system tests may have no user.
            tested_by   VARCHAR(120),
            CONSTRAINT fk_printer_test_history_printer
                FOREIGN KEY (printer_id) REFERENCES public.printer(id)
                ON DELETE CASCADE
        );

        -- Primary access pattern: last-N attempts for one printer,
        -- newest first. Compound (printer_id, tested_at DESC) covers it.
        CREATE INDEX idx_printer_test_history_printer_tested
            ON public.printer_test_history (printer_id, tested_at DESC);

        COMMENT ON TABLE public.printer_test_history IS
            'PR-Printer-R9.5a: per-attempt test-print log. Feeds the FE PrinterDetailsPanel history section for intermittent-flake diagnosis.';
    END IF;
END
$$;
