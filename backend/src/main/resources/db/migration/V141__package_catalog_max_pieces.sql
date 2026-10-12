-- Add max_pieces to carrier_package_catalog + package_preset so the
-- FE picker can warn (and submit guard can block) when an operator
-- picks a single-piece-only packaging alongside 2+ boxes.
--
-- Replaces the hardcoded UPS 01 (Letter) tactical check in
-- NewShipmentPage.singlePieceOnlyWarning (commit c3eb6f5c). Known
-- single-piece-only presets today: UPS Letter (code=01). USPS Flat
-- Rate Envelope IS multi-piece-capable; FedEx envelope IS MP capable;
-- DHL envelopes are MP capable. Leave null elsewhere = no limit.
--
-- Idempotent (see docs/flyway-fresh-db-guard-pattern.md).
DO $$
BEGIN
    IF to_regclass('public.carrier_package_catalog') IS NOT NULL THEN
        ALTER TABLE carrier_package_catalog
            ADD COLUMN IF NOT EXISTS max_pieces INTEGER;
        UPDATE carrier_package_catalog
           SET max_pieces = 1
         WHERE carrier_code = 'UPS' AND code = '01';
    END IF;

    IF to_regclass('public.package_preset') IS NOT NULL THEN
        ALTER TABLE package_preset
            ADD COLUMN IF NOT EXISTS max_pieces INTEGER;
        -- Also apply to any already-synced UPS Letter preset so the
        -- next FE page load gets the guard without a resync.
        UPDATE package_preset
           SET max_pieces = 1
         WHERE carrier = 'UPS' AND carrier_package_code = '01';
    END IF;
END $$;
