-- V70 - Printer R7a: per (client, carrier) commercial-invoice copies.
--
-- Background: some carriers require multiple physical copies of the
-- commercial invoice (FedEx typically 3, DHL 4, etc.). The value also
-- varies per tenant/client contract. The old model always printed 1.
--
-- Rule resolution (see InvoiceCopiesService.resolveCopies):
--   1. exact match on (client_code, carrier_code) → use that value
--   2. tenant-default match on (NULL client_code, carrier_code) → use that
--   3. no rule → 1 copy (hardcoded fallback)
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('public.invoice_copies') IS NULL THEN
        CREATE TABLE public.invoice_copies (
            id           BIGSERIAL PRIMARY KEY,
            -- NULL means "tenant-wide default for this carrier"; a real
            -- client_code overrides. The unique constraint below relies
            -- on Postgres NULL-inequality treating each NULL as distinct
            -- BUT we also want at most one wildcard row per carrier, so
            -- COALESCE-based partial unique index guards that.
            client_code  VARCHAR(50),
            carrier_code VARCHAR(30)  NOT NULL,
            copies       INT          NOT NULL DEFAULT 1 CHECK (copies BETWEEN 1 AND 20),
            created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        );

        -- Enforce uniqueness on (client_code, carrier_code) with NULLs
        -- treated as a distinct sentinel value. Postgres 15+ supports
        -- NULLS NOT DISTINCT; use COALESCE for compat with older engines.
        CREATE UNIQUE INDEX uk_invoice_copies_client_carrier
            ON public.invoice_copies (COALESCE(client_code, ''), carrier_code);

        COMMENT ON TABLE public.invoice_copies IS
            'PR-Printer-R7a: how many commercial-invoice copies to print per (client, carrier). NULL client_code = tenant default; explicit client wins. Missing rule = 1 copy.';

        COMMENT ON COLUMN public.invoice_copies.client_code IS
            'NULL = tenant-wide default row for this carrier. Non-NULL = per-client override.';

        COMMENT ON COLUMN public.invoice_copies.copies IS
            'Physical copies to emit at print time. Bounded 1..20 to catch typo-driven runaway prints.';
    END IF;
END
$$;
