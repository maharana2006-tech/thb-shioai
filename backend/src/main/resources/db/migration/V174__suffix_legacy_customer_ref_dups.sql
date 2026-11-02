-- R2-BUG-005 follow-up — clean up legacy duplicate customer_ref rows so
-- V175 can promote the dup-reference index from "cutoff UNIQUE" to
-- "full-history UNIQUE".
--
-- State on the dev DB at migration time (2026-10-20):
--   - 10,081 duplicate groups by (upper(cust_no), lower(customer_ref))
--     over the GENERATED subset.
--   - 20,236 rows total in dup groups; **0 of them carry a tracking
--     number**. All zero-tracking test/preseed data, mostly from a
--     one-off batch on 2026-09-12 (19,854 rows). Not real shipments —
--     safe to mutate.
--   - customer_ref column max = varchar(80); longest ref currently in a
--     dup group = 14 chars; max orderNo ~7 digits + '#' = 8 chars extra.
--     Suffixed values stay well under 80.
--
-- Approach: keep the earliest-by-order_no row in each group at its
-- original customer_ref so operator lookups still resolve. Append
-- '#<orderNo>' to every other row in the group, making each row's
-- (cust_no, customer_ref) uniquely identifiable.
--
-- Fresh-DB guard per docs/flyway-fresh-db-guard-pattern.md — the
-- UPDATE no-ops when no dup groups exist, so new deployments get a
-- clean skip without needing a to_regclass check.

DO $$
DECLARE
    affected INTEGER;
BEGIN
    IF to_regclass('public.label_batch') IS NULL THEN
        RETURN;
    END IF;

    WITH ranked AS (
        SELECT lb.order_no,
               row_number() OVER (
                   PARTITION BY upper(lb.cust_no), lower(lb.customer_ref)
                   ORDER BY lb.order_no
               ) AS rn
          FROM label_batch lb
         WHERE lb.customer_ref IS NOT NULL
           AND lb.order_status = 'GENERATED'
    ),
    redundant AS (
        SELECT order_no FROM ranked WHERE rn > 1
    )
    UPDATE label_batch lb
       SET customer_ref = lb.customer_ref || '#' || lb.order_no::text
      FROM redundant r
     WHERE lb.order_no = r.order_no;

    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V174: suffixed % redundant customer_ref row(s) with #<orderNo>', affected;
END $$;
