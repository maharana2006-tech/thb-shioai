-- R2-BUG-005 follow-up — promote the dup-reference index to a single
-- full-history UNIQUE now that V174 has suffixed every legacy duplicate
-- customer_ref with '#<orderNo>'.
--
-- Before this migration:
--   V171 ix_label_batch_client_ref_active        (non-unique, full history,
--                                                 pre-dup-cleanup fallback)
--   V173 uniq_label_batch_client_ref_active_v2   (UNIQUE, post-cutoff only)
--
-- After this migration:
--   V175 uniq_label_batch_client_ref_active_v3   (UNIQUE, full history)
--
-- The two old indexes are dropped — superseded by _v3. The exception
-- translator in CarrierServiceImpl.isDuplicateReferenceConstraint uses
-- .contains("uniq_label_batch_client_ref_active"), so the _v3 name
-- still matches the 409 LABEL_ALREADY_GENERATED translation. Zero Java
-- change.
--
-- With V174's dedup done, this CREATE UNIQUE INDEX should succeed on
-- the first try (no EXCEPTION fallback needed). The DO block still
-- guards for fresh-DB + idempotent reruns.

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NULL THEN
        RETURN;
    END IF;

    -- Idempotency: already promoted?
    IF to_regclass('public.uniq_label_batch_client_ref_active_v3') IS NOT NULL THEN
        RETURN;
    END IF;

    CREATE UNIQUE INDEX uniq_label_batch_client_ref_active_v3
        ON label_batch (upper(cust_no), lower(customer_ref))
        WHERE customer_ref IS NOT NULL
          AND order_status = 'GENERATED';

    -- The superseded indexes stay as data history — DROP IF EXISTS so
    -- a fresh DB (where neither existed) skips cleanly. Keep the
    -- INDEX IF EXISTS guard order: unique first, then non-unique.
    DROP INDEX IF EXISTS uniq_label_batch_client_ref_active_v2;
    DROP INDEX IF EXISTS ix_label_batch_client_ref_active;
END $$;
