-- V94 — nds-default: Bulk Mailer parcels never write to NDS.
--
-- Per ShipX_NDS_Orders_and_Tracking.docx §8 + §10 ("Bulk Mailer parcels
-- are not NDS orders"), the operator contract is that Bulk Mailer never
-- pushes anything back into NDS. V93 added writeback_source_bulk on
-- external_system_connection with default TRUE — this seed flips the
-- nds-default row to FALSE so it matches the doc.
--
-- Other connections (customer-added REST endpoints, future NDS
-- environments) keep the TRUE default. An ops user who wants Bulk→NDS
-- on nds-default explicitly can toggle it back from the Writeback tab
-- on /settings/external-systems.
--
-- Fresh-DB safe: no-op when the nds-default row hasn't been seeded yet.

DO $$
BEGIN
    UPDATE public.external_system_connection
        SET writeback_source_bulk = FALSE
        WHERE name = 'nds-default'
          AND writeback_source_bulk IS DISTINCT FROM FALSE;
END
$$;
