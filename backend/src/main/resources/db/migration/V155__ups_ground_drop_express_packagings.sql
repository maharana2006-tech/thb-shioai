-- BUG-089 — Shipping Catalog screen description says ground-family
-- services don't accept branded packaging, but UPS Ground was linked
-- to 5 UPS Express-branded packagings (Express Pak, 3x Express Boxes,
-- Tube) plus UPS 25KG Box. Operators picking Ground + any of those
-- packagings would get a carrier reject at label time. Delete the
-- service_package links so the screen rule holds.
--
-- Idempotent: WHERE joins return zero rows after the first apply.
-- Ground vs non-Ground is identified by the "ground"-family service
-- name (case-insensitive).
DO $$
BEGIN
    IF to_regclass('public.service_package') IS NOT NULL
       AND to_regclass('public.shipping_service') IS NOT NULL
       AND to_regclass('public.carrier_package_catalog') IS NOT NULL THEN
        DELETE FROM service_package sp
         WHERE sp.id IN (
             SELECT sp2.id FROM service_package sp2
               JOIN shipping_service ss ON ss.id = sp2.service_id
               JOIN carrier_package_catalog cpc ON cpc.id = sp2.preset_id
              WHERE cpc.carrier_code = 'UPS'
                AND ss.name ILIKE '%ground%'
                AND (cpc.name ILIKE '%express%'
                     OR cpc.name ILIKE '%pak%'
                     OR cpc.name ILIKE '%tube%'
                     OR cpc.name ILIKE '%letter%'
                     OR cpc.name ILIKE '%10kg%'
                     OR cpc.name ILIKE '%25kg%')
         );
    END IF;
END $$;
