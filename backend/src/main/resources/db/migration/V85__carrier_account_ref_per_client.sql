-- V82 — allow multiple clients to share the same (account_number, carrier_code).
--
-- Prior state: unique constraint `uk_account_ref_number_carrier` on
-- (account_number, carrier_code). This treated a carrier account as
-- globally unique — every client trying to add the "same" account
-- collided on the natural key and the upsert path in AccountRefServiceImpl
-- silently overwrote the existing row's customer_no, effectively moving
-- the account from Client A to Client B without warning.
--
-- New state: two partial unique indexes so multiple clients can each
-- hold their own copy of the same physical carrier account:
--   1. uk_account_ref_number_carrier_client (account_number, carrier_code,
--      customer_no) WHERE customer_no IS NOT NULL — one row per
--      (account, carrier, client).
--   2. uk_account_ref_number_carrier_platform (account_number,
--      carrier_code) WHERE customer_no IS NULL — still exactly one
--      platform row per (account, carrier). Platform accounts are
--      shared across every tenant, so duplicates make no sense.
--
-- Fresh-DB-safe via to_regclass guard (see docs/flyway-fresh-db-guard-pattern.md).
-- No code path breaks on rollout — the old constraint's rows are all
-- still valid under the new indexes (no data migration required).

DO $$
BEGIN
    IF to_regclass('public.carrier_account_ref') IS NOT NULL THEN
        ALTER TABLE carrier_account_ref
            DROP CONSTRAINT IF EXISTS uk_account_ref_number_carrier;

        CREATE UNIQUE INDEX IF NOT EXISTS uk_account_ref_number_carrier_client
            ON carrier_account_ref (
                LOWER(account_number),
                LOWER(carrier_code),
                LOWER(customer_no))
            WHERE customer_no IS NOT NULL;

        CREATE UNIQUE INDEX IF NOT EXISTS uk_account_ref_number_carrier_platform
            ON carrier_account_ref (
                LOWER(account_number),
                LOWER(carrier_code))
            WHERE customer_no IS NULL;
    END IF;
END $$;
