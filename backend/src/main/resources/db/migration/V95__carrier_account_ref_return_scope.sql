-- V95 — carrier_account_ref.return_scope
--
-- Per-account flag declaring what geographic scope of return labels this
-- account is entitled to. Replaces the hardcoded FedEx/UPS-only check
-- added in G9 (PR-A3, 11b95f4e) with a data-driven gate.
--
-- Values:
--   DOMESTIC_ONLY               — default; return only when from + to are same country
--   DOMESTIC_AND_INTERNATIONAL  — return allowed on any lane
--   DISABLED                    — return labels rejected on this account
--
-- Default DOMESTIC_ONLY on every existing row because that matches the
-- prior hardcoded behavior for FedEx / UPS accounts and is the safer
-- position for the rare accounts that had no returns before.
--
-- Editable in Settings → Carrier Accounts (FE follow-up). Fresh-DB safe.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'carrier_account_ref'
          AND column_name = 'return_scope'
    ) THEN
        ALTER TABLE public.carrier_account_ref
            ADD COLUMN return_scope VARCHAR(30) NOT NULL DEFAULT 'DOMESTIC_ONLY';
        COMMENT ON COLUMN public.carrier_account_ref.return_scope IS
            'V95 — return-label eligibility: DOMESTIC_ONLY | DOMESTIC_AND_INTERNATIONAL | DISABLED.';
    END IF;
END
$$;
