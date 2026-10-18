-- BUG-017 retro-fix. The code fix (CarrierServiceImpl.aggregateShipmentWeight)
-- only takes effect on NEW shipments; pre-fix MPS orders still have
-- label_batch.weight set to the first box's weight. Sum per-package
-- weights into the shipment total so the Order API's shippingDetails.weight
-- stops lying about multi-piece shipments.
--
-- Idempotent: WHERE package_count > 1 limits updates to MPS orders;
-- re-running against an already-backfilled row is a no-op (it writes
-- the same sum). Rows with mixed weight_units are skipped — those are
-- a different data-quality problem than this fix is for.
DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NOT NULL
       AND to_regclass('public.label_package') IS NOT NULL THEN
        UPDATE label_batch b
           SET weight = sub.total
          FROM (
            SELECT order_no, SUM(weight) AS total,
                   COUNT(DISTINCT weight_unit) AS unit_count
              FROM label_package
             WHERE weight IS NOT NULL
             GROUP BY order_no
          ) sub
         WHERE b.order_no = sub.order_no
           AND COALESCE(b.package_count, 1) > 1
           AND sub.unit_count <= 1;
    END IF;
END $$;
