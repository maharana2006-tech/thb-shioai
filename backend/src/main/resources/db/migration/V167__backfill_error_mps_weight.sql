-- BUG-017 R7: V150 only backfilled label_batch.weight on MPS orders that
-- had label_package rows (written on carrier-success). ERROR / never-labelled
-- MPS orders still carry one box's weight because label_package was never
-- populated for them. For these rows, parse packages_json (V33) — the
-- intended-shipment packages payload — and sum per-package weights.
--
-- Idempotent: WHERE package_count > 1 + no existing label_package rows
-- narrows to the dead set; a re-run after a successful label (which would
-- have added label_package rows) is a no-op because the outer filter stops
-- matching.
DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NULL THEN
        RAISE NOTICE 'V167 skipped — label_batch missing';
        RETURN;
    END IF;

    -- Pull weight array from packages_json. We're on Postgres 15+, so
    -- jsonb_path_query_array handles the extraction. Rows whose JSON has
    -- mixed weight_units are skipped — like V150, that's a different
    -- data-quality problem.
    UPDATE label_batch b
       SET weight = sub.total
      FROM (
        SELECT b2.order_no,
               (SELECT SUM((p.value ->> 'weight')::numeric)
                  FROM jsonb_array_elements(b2.packages_json::jsonb) p
                 WHERE p.value ->> 'weight' IS NOT NULL) AS total,
               (SELECT COUNT(DISTINCT p.value ->> 'weightUnit')
                  FROM jsonb_array_elements(b2.packages_json::jsonb) p
                 WHERE p.value ->> 'weightUnit' IS NOT NULL) AS unit_count
          FROM label_batch b2
         WHERE b2.packages_json IS NOT NULL
           AND COALESCE(b2.package_count, 1) > 1
           -- Only orders that never generated per-package rows (i.e. V150
           -- couldn't touch them).
           AND NOT EXISTS (
               SELECT 1 FROM label_package lp WHERE lp.order_no = b2.order_no
           )
      ) sub
     WHERE b.order_no = sub.order_no
       AND sub.total IS NOT NULL
       AND sub.unit_count <= 1;
END $$;
