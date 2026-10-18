-- BUG-154 R7: four UPS packages (Large / Medium / Small Express Box
-- and Tube) landed with max_weight NULL in carrier_package_catalog, so
-- the order form's 'smallest box whose max fits' auto-pick treated them
-- as unlimited. UPS tariff max weight for all four is 30 lb (Express
-- Box) / 20 lb (Small Express Box, Tube).
--
-- Idempotent: WHERE max_weight IS NULL narrows to the dead rows; a
-- re-run updates zero after the first apply. Rows that already carry a
-- max_weight stay untouched (admin-override wins).
DO $$
BEGIN
    IF to_regclass('public.carrier_package_catalog') IS NULL THEN
        RAISE NOTICE 'V166 skipped — carrier_package_catalog missing';
        RETURN;
    END IF;

    UPDATE carrier_package_catalog
       SET max_weight = 30
     WHERE carrier_code = 'UPS'
       AND max_weight IS NULL
       AND (name ILIKE '%Large Express Box%' OR name ILIKE '%Medium Express Box%');

    UPDATE carrier_package_catalog
       SET max_weight = 20
     WHERE carrier_code = 'UPS'
       AND max_weight IS NULL
       AND (name ILIKE '%Small Express Box%' OR name ILIKE '%UPS Tube%');
END $$;
