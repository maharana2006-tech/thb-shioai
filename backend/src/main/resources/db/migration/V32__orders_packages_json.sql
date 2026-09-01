-- Sprint 52 — persist the intended packages payload on the Order row so
-- the auto path (POST /orders/{orderNo}/label, aka CarrierServiceImpl.
-- generateLabel) can reconstruct the multi-box shipment when the operator
-- retries a failed multi-pkg attempt.
--
-- Pre-fix, generateLabel built the ShipmentRequestDTO from Order scalar
-- fields alone (single weight/dims). Any multi-pkg intent from the
-- original manual-label call was lost — even if Order.package_count was 2,
-- the auto path had nowhere to read per-box dimensions/weight/reference
-- from. See issue #545 for the full trace.
--
-- Column: TEXT on label_batch (the physical table backing the Order
-- entity — the "orders" name is a JPA-level rename). Matches the
-- existing pattern used by label_batch.importer_broker_override (also
-- JSON-shaped text serialized via Jackson at the service boundary, not
-- Hibernate @Convert). Simpler, avoids Hibernate-JSON-type quirks, no
-- migration cost if the column later needs to be queried structurally.
--
-- Fresh-DB pattern per docs/flyway-fresh-db-guard-pattern.md: to_regclass
-- gate + IF NOT EXISTS so a first-time deploy that hasn't yet run V1
-- (which creates label_batch) gets a skip-notice rather than a hard fail.
DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NULL THEN
        RAISE NOTICE 'V32 skipped — label_batch missing (fresh DB before first sync)';
        RETURN;
    END IF;

    ALTER TABLE label_batch
        ADD COLUMN IF NOT EXISTS packages_json TEXT;

    COMMENT ON COLUMN label_batch.packages_json IS
        'Intended per-box package details captured at order-creation time '
        || '(from ManualShipmentRequest.packages). Read by the auto label-'
        || 'generation path so a retry/regenerate can reconstruct the '
        || 'multi-box shipment instead of collapsing to 1 pkg. Nullable — '
        || 'single-box legacy orders keep the pre-V32 shape.';

    RAISE NOTICE 'V32 added label_batch.packages_json (TEXT)';
END $$;
