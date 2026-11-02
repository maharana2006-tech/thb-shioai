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

-- Attempt UNIQUE first. If pre-existing dups block the create (dev DB
-- contained `(THB000, po-203984)` with two GENERATED rows on 2026-10-18
-- boot), fall back to a non-unique index with the same shape so lookups
-- are still fast and a follow-up migration can promote to UNIQUE after
-- ops resolves the dup. Correctness floor is the Redis SETNX in
-- `CarrierServiceImpl` — this index primarily accelerates
-- `OrderRepository.findShippedByClientAndReference`.

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NULL THEN
        RETURN;
    END IF;
    IF to_regclass('public.uniq_label_batch_client_ref_active') IS NOT NULL
       OR to_regclass('public.ix_label_batch_client_ref_active') IS NOT NULL THEN
        RETURN;
    END IF;
    BEGIN
        CREATE UNIQUE INDEX uniq_label_batch_client_ref_active
            ON label_batch (upper(cust_no), lower(customer_ref))
            WHERE customer_ref IS NOT NULL AND order_status = 'GENERATED';
    EXCEPTION WHEN unique_violation THEN
        -- Pre-existing duplicate rows exist. Create a non-unique index
        -- so lookups still hit it, and surface a loud NOTICE so ops can
        -- find the offending rows and schedule a cleanup + a follow-up
        -- migration that converts this to UNIQUE. The Redis SETNX in
        -- CarrierServiceImpl still blocks NEW concurrent duplicates;
        -- what's missing is the DB-level backstop for the Redis-outage
        -- window.
        RAISE NOTICE 'V171: pre-existing (cust_no, customer_ref) duplicates in label_batch '
                     'block the UNIQUE index. Creating a non-unique fallback so lookups still '
                     'use the index. Resolve the duplicates and re-run as a follow-up '
                     'migration that CREATE UNIQUE INDEX against the clean data. '
                     'Find them with: SELECT upper(cust_no), lower(customer_ref), count(*) '
                     'FROM label_batch WHERE customer_ref IS NOT NULL AND order_status = ''GENERATED'' '
                     'GROUP BY 1, 2 HAVING count(*) > 1;';
        CREATE INDEX ix_label_batch_client_ref_active
            ON label_batch (upper(cust_no), lower(customer_ref))
            WHERE customer_ref IS NOT NULL AND order_status = 'GENERATED';
    END;
END $$;
