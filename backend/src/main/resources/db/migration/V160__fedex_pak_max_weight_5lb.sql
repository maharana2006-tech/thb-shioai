-- BUG-097 — Carrier Limits said FEDEX_PAK = 5 lb max while Shipping
-- Catalog had maxWeight 3 lb. A 4 lb parcel passed the limit check but
-- the Catalog refused the preset selection. Operator picks: Carrier
-- Limits is the policy source — harmonise Catalog to 5 lb for FEDEX_PAK
-- and UPS Express Pak (same carrier doc guidance).
--
-- Idempotent: UPDATE is a no-op once rows carry 5.
DO $$
BEGIN
    IF to_regclass('public.carrier_package_catalog') IS NOT NULL THEN
        UPDATE carrier_package_catalog
           SET max_weight = 5
         WHERE carrier_code IN ('FEDEX', 'UPS')
           AND (name ILIKE '%pak%' OR code ILIKE '%pak%' OR code = 'FEDEX_PAK' OR code = '04')
           AND (max_weight IS NULL OR max_weight < 5);
    END IF;
END $$;
