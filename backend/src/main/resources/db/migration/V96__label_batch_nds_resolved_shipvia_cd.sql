-- V96 — label_batch.nds_resolved_shipvia_cd
--
-- Canonical ERP code for NDS OEHEAD.SHIPVIA_CD writeback, derived by
-- resolving the incoming SHIPVIA (STD, P80, F03, whatever the source
-- carried) through the client's Shipping Service Mapping. Persisted on
-- every Order row so the value survives regenerate / async / retry
-- pathways without needing to re-run the resolver.
--
-- Consumers:
--   • Every label-generate path populates the column at Order save time
--     using ShippingConfigService's mapping reverse-lookup.
--   • NdsShipmentOracleWriter reads it at writeback time; if non-null
--     AND it differs from OEHEAD.SHIPVIA_CD, emits an UPDATE. Doubles as
--     the STD-replacement writeback signal that PR-C1 previously threaded
--     through the request DTO.
--
-- Nullable — pre-V96 rows and shipments that don't touch NDS (Bulk
-- Mailer per doc §8, external API without an NDS-mapped client) have
-- no value and the writer simply skips the OEHEAD update for them.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'label_batch'
          AND column_name = 'nds_resolved_shipvia_cd'
    ) THEN
        ALTER TABLE public.label_batch
            ADD COLUMN nds_resolved_shipvia_cd VARCHAR(20);
        COMMENT ON COLUMN public.label_batch.nds_resolved_shipvia_cd IS
            'V96 — canonical ERP ship-via for NDS OEHEAD writeback; null when the shipment doesn''t map to any NDS client.';
    END IF;
END
$$;
