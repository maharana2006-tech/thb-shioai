-- R2-BUG-005 (run-4) — concurrent-duplicate correctness backstop.
--
-- The AM BUG-006 fix added a read-then-check `findShippedByClientAndReference`
-- guard in `CarrierServiceImpl.generateManualLabelLegacyBody`. Sequential
-- duplicates (second POST after the first commits) hit it and 409. But
-- concurrent duplicates (two or three POSTs arriving in the same ms) all
-- miss the SELECT and all buy labels — the retest reproduced it 3/3.
--
-- The parallel code change adds a Redis SETNX fast-path dedup to short-
-- circuit the race BEFORE the carrier call (no wasted label, no cleanup).
-- This index is the correctness floor: it survives a Redis outage, a TTL
-- miss, or an app crash between SETNX and the DB write. Postgres rejects
-- any second insert with the same (upper(cust_no), lower(customer_ref))
-- when both rows have `order_status = 'GENERATED'` and `customer_ref` is
-- non-null. The application-side `DataIntegrityViolationException` catch
-- translates it to the same 409 LABEL_ALREADY_GENERATED the SELECT guard
-- returns, so callers see a consistent code regardless of which layer
-- caught the race.
--
-- Partial-index predicate rationale:
--   - `customer_ref IS NOT NULL` — a label without a reference is a
--     one-off whose "dup" semantic is undefined; the AM guards skip
--     those too.
--   - `order_status = 'GENERATED'` — voided labels free up the reference
--     for a reissue (matches R2-BUG-016's VOIDED-aware behaviour on the
--     Validate dup-check). Without this clause, a voided original would
--     block its own reissue.
--
-- Case-folding (`upper(cust_no)`, `lower(customer_ref)`) mirrors the
-- SELECT in OrderRepository#findShippedByClientAndReference so both
-- guards agree on uniqueness.
--
-- `IF NOT EXISTS` + `to_regclass` guard for fresh-DB + idempotent reruns
-- per the project's Flyway convention
-- (docs/flyway-fresh-db-guard-pattern.md).

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NOT NULL
       AND to_regclass('public.uniq_label_batch_client_ref_active') IS NULL THEN
        CREATE UNIQUE INDEX uniq_label_batch_client_ref_active
            ON label_batch (upper(cust_no), lower(customer_ref))
            WHERE customer_ref IS NOT NULL AND order_status = 'GENERATED';
    END IF;
END $$;
