-- R2-BUG-005 (run-5) — DB correctness floor for concurrent duplicate
-- label purchases.
--
-- V171 attempted CREATE UNIQUE INDEX uniq_label_batch_client_ref_active,
-- hit pre-existing duplicate rows in label_batch (10,081 groups /
-- 10,155 redundant rows on the test DB, all legacy THB000 shipments
-- from 2026-09-10 with blank tracking numbers — pre-fix noise) and
-- fell back to a non-unique index via EXCEPTION WHEN unique_violation.
-- That leaves Redis SETNX as the only correctness guard; a Redis
-- outage window exposes concurrent duplicates again.
--
-- Scope the UNIQUE enforcement to shipments created on-or-after the
-- cutoff date so historic dup groups are left as-is (they're legacy
-- data operators may still look up by their old customer_ref) while
-- every NEW shipment is covered by the DB floor regardless of
-- Redis's state. Postgres accepts the literal date in the partial-
-- index predicate as immutable; new inserts with created_date >=
-- cutoff match the predicate and are checked against the index.
--
-- The existing non-unique ix_label_batch_client_ref_active (V171)
-- stays in place — SELECT guards in OrderRepository.findShippedByClient
-- AndReference scan all history, not just post-cutoff rows, so the
-- lookup index needs to cover the full table.
--
-- The exception translator in CarrierServiceImpl.isDuplicate
-- ReferenceConstraint at :1525 uses `.contains("uniq_label_batch_
-- client_ref_active")` — "uniq_label_batch_client_ref_active" is a
-- SUBSTRING of "uniq_label_batch_client_ref_active_v2", so the
-- substring match catches both names. Zero Java change required.
--
-- Fresh-DB guard + idempotent re-run per
-- docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NULL THEN
        RETURN;
    END IF;
    IF to_regclass('public.uniq_label_batch_client_ref_active_v2') IS NOT NULL THEN
        RETURN;
    END IF;
    CREATE UNIQUE INDEX uniq_label_batch_client_ref_active_v2
        ON label_batch (upper(cust_no), lower(customer_ref))
        WHERE customer_ref IS NOT NULL
          AND order_status = 'GENERATED'
          AND created_date >= DATE '2026-10-21';
END $$;
