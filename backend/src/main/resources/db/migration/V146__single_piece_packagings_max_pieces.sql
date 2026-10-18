-- BUG-003 — carrier-authoritative single-piece packagings. QA R2 observed
-- FedEx Envelope / 10kg / 25kg box and UPS 10KG / 25KG box all accepted a
-- second box in the FE picker (max_pieces NULL = no limit) then the
-- carrier rejected the label with:
--   FEDEX_ENVELOPE   : "PACKAGINGTYPE.ENVELOPE.MULTIPLE.ERROR"
--   FEDEX_10KG_BOX   : "SINGLEPIECE.ONLY.ERROR"
--   FEDEX_25KG_BOX   : "SINGLEPIECE.ONLY.ERROR"
--   UPS 24 / 25      : UPS 111375 "service not valid for packaging"
--
-- Set max_pieces=1 on each so the FE picker gates the add-box UI (same
-- path as UPS Letter V141). Also propagate to any package_preset rows
-- already synced from these catalog entries so the gate kicks in on
-- the current dev DB without a resync.
--
-- Idempotent (see docs/flyway-fresh-db-guard-pattern.md).
DO $$
BEGIN
    IF to_regclass('public.carrier_package_catalog') IS NOT NULL THEN
        UPDATE carrier_package_catalog
           SET max_pieces = 1
         WHERE max_pieces IS NULL
           AND (
               (carrier_code = 'FEDEX' AND code IN ('FEDEX_ENVELOPE', 'FEDEX_10KG_BOX', 'FEDEX_25KG_BOX'))
               OR (carrier_code = 'UPS' AND code IN ('24', '25'))
           );
    END IF;

    IF to_regclass('public.package_preset') IS NOT NULL THEN
        UPDATE package_preset
           SET max_pieces = 1
         WHERE max_pieces IS NULL
           AND (
               (carrier = 'FEDEX' AND carrier_package_code IN ('FEDEX_ENVELOPE', 'FEDEX_10KG_BOX', 'FEDEX_25KG_BOX'))
               OR (carrier = 'UPS' AND carrier_package_code IN ('24', '25'))
           );
    END IF;
END $$;
