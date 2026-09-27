-- V92 — remove FEDEX_10KG_BOX and FEDEX_25KG_BOX from INTERNATIONAL_ECONOMY.
--
-- V40 (authoritative service_package matrix) seeded these two boxes for BOTH
-- INTERNATIONAL_PRIORITY and INTERNATIONAL_ECONOMY. Real FedEx rule: 10kg /
-- 25kg boxes are Priority-only. Economy rejects them at the carrier with
-- "Packaging type is not allowed" (F4 from MKL246 combined validation run
-- 2026-09-27).
--
-- Fresh-DB safe: the DELETE is a no-op on installs without the row.

DO $$
BEGIN
    DELETE FROM service_package sp
    USING shipping_service ss, package_preset pp
    WHERE sp.service_id = ss.id
      AND sp.preset_id  = pp.id
      AND ss.carrier    = 'FEDEX'
      AND ss.service_code = 'INTERNATIONAL_ECONOMY'
      AND pp.carrier    = 'FEDEX'
      AND pp.carrier_package_code IN ('FEDEX_10KG_BOX', 'FEDEX_25KG_BOX');
END
$$;
