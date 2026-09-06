-- Rewrite `service_package` from authoritative carrier rules. Pre-fix,
-- seed data linked services to packages that the real carrier API
-- rejects at wire time — e.g. UPS 3 Day Select ↔ Express Pak was in
-- the table, but UPS Ship API rejects the combo with
-- "121500: The selected service is not valid with the selected
-- packaging." The FE already filters the package picker by these
-- rows (NewShipmentPage.tsx:1029 compatiblePresetIdsForService), so
-- the operator was offered invalid combinations and only found out
-- at label generation.
--
-- Rules encoded here are from UPS Ship API v2409 + FedEx Ship API
-- v2 / Rate & Ship Rules pack (both authoritative carrier docs).
-- CUSTOM presets (kind='CUSTOM') are handled separately by the
-- PackagingCompatibilityGuard "implicit-allowed" short-circuit
-- (CarrierServiceImpl.java:1161-1167) so they don't need rows here.
--
-- UPS canonical matrix (Ship API v2409):
--   Ground/hybrid (03, 11, 12): CSP only → NO carrier presets linked
--     (operator uses YOUR_PACKAGING / CUSTOM only)
--   Domestic Air (01, 02): Letter, Pak, Tube, Sm/Md/Lg Express Box
--     (10KG/25KG boxes are INTL-only, not permitted domestic)
--   Intl Worldwide Express (07, 54, 65): all air presets + 10KG/25KG
--   Intl Worldwide Expedited (08): Pak + 10KG + 25KG only
--     (Letter/Tube/Small/Med/Large NOT permitted on Expedited)
--
-- FedEx canonical matrix (Ship API v2):
--   Ground services (FEDEX_GROUND, GROUND_HOME_DELIVERY): YOUR_PACKAGING
--     only → NO carrier presets linked
--   Domestic Air (2_DAY, EXPRESS_SAVER, PRIORITY, PRIORITY_OVERNIGHT,
--     STANDARD_OVERNIGHT): all 8 domestic FedEx presets (Envelope, Pak,
--     Tube, 10KG Box, 25KG Box, Small/Medium/Large/XL Box)
--   Intl (INTERNATIONAL_PRIORITY, INTERNATIONAL_ECONOMY,
--     INTERNATIONAL_FIRST, EUROPE_FIRST_INTERNATIONAL_PRIORITY):
--     Envelope + Pak + Tube + 10KG Box + 25KG Box (NOT Small/Med/Large
--     one-rate — those are US-domestic-only)
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: DELETE + INSERT
-- pattern rewrites the entire UPS+FEDEX subset each run.
-- See docs/flyway-fresh-db-guard-pattern.md.
DO $$
BEGIN
    IF to_regclass('public.service_package') IS NULL
       OR to_regclass('public.shipping_service') IS NULL
       OR to_regclass('public.package_preset') IS NULL THEN
        RAISE NOTICE 'V40 skipped — required tables missing (fresh DB before first sync)';
        RETURN;
    END IF;

    -- Wipe only the UPS + FedEx subset. Leaves any (future) DHL/USPS
    -- rows untouched — they can be seeded when those carriers gain
    -- service_package awareness.
    DELETE FROM service_package sp
    USING shipping_service ss
    WHERE sp.service_id = ss.id
      AND ss.carrier IN ('UPS', 'FEDEX');

    -- UPS DOMESTIC AIR: Next Day Air (01) + 2nd Day Air (02)
    -- Letter, Express Pak, Tube, Sm/Md/Lg Express Box (no 10KG/25KG)
    INSERT INTO service_package (service_id, preset_id)
    SELECT ss.id, pp.id
    FROM shipping_service ss
    CROSS JOIN package_preset pp
    WHERE ss.carrier = 'UPS'
      AND ss.service_code IN ('01', '02')
      AND pp.carrier = 'UPS'
      AND pp.carrier_package_code IN ('01', '03', '04', '2a', '2b', '2c')
    ON CONFLICT (service_id, preset_id) DO NOTHING;

    -- UPS INTL WORLDWIDE EXPRESS variants: 07, 54, 65
    -- All air presets + 10KG + 25KG (heavy intl boxes)
    INSERT INTO service_package (service_id, preset_id)
    SELECT ss.id, pp.id
    FROM shipping_service ss
    CROSS JOIN package_preset pp
    WHERE ss.carrier = 'UPS'
      AND ss.service_code IN ('07', '54', '65')
      AND pp.carrier = 'UPS'
      AND pp.carrier_package_code IN ('01', '03', '04', '2a', '2b', '2c', '24', '25')
    ON CONFLICT (service_id, preset_id) DO NOTHING;

    -- UPS INTL WORLDWIDE EXPEDITED: 08
    -- Only Express Pak + 10KG + 25KG (no Letter/Tube/Sm/Md/Lg)
    INSERT INTO service_package (service_id, preset_id)
    SELECT ss.id, pp.id
    FROM shipping_service ss
    CROSS JOIN package_preset pp
    WHERE ss.carrier = 'UPS'
      AND ss.service_code = '08'
      AND pp.carrier = 'UPS'
      AND pp.carrier_package_code IN ('04', '24', '25')
    ON CONFLICT (service_id, preset_id) DO NOTHING;

    -- UPS Ground (03) + 3 Day Select (12) + Standard (11): NO CARRIER
    -- presets. Operator picks CUSTOM ("Your boxes") or falls back to
    -- YOUR_PACKAGING. Deliberately skipping the INSERT.

    -- FEDEX DOMESTIC AIR: all overnight + 2-day + express-saver + priority
    -- All 9 domestic FedEx presets (Envelope, Pak, Tube, 10KG, 25KG,
    -- Small/Medium/Large/XL Box)
    INSERT INTO service_package (service_id, preset_id)
    SELECT ss.id, pp.id
    FROM shipping_service ss
    CROSS JOIN package_preset pp
    WHERE ss.carrier = 'FEDEX'
      AND ss.service_code IN (
          'FEDEX_2_DAY', 'FEDEX_EXPRESS_SAVER', 'FEDEX_PRIORITY',
          'PRIORITY_OVERNIGHT', 'STANDARD_OVERNIGHT')
      AND pp.carrier = 'FEDEX'
      AND pp.carrier_package_code IN (
          'FEDEX_ENVELOPE', 'FEDEX_PAK', 'FEDEX_TUBE',
          'FEDEX_10KG_BOX', 'FEDEX_25KG_BOX',
          'FEDEX_SMALL_BOX', 'FEDEX_MEDIUM_BOX',
          'FEDEX_LARGE_BOX', 'FEDEX_EXTRA_LARGE_BOX')
    ON CONFLICT (service_id, preset_id) DO NOTHING;

    -- FEDEX INTL: Priority + Economy + First + Europe First Priority
    -- Envelope + Pak + Tube + 10KG + 25KG (US-domestic-only "One Rate"
    -- Small/Medium/Large/XL boxes are NOT accepted on intl services)
    INSERT INTO service_package (service_id, preset_id)
    SELECT ss.id, pp.id
    FROM shipping_service ss
    CROSS JOIN package_preset pp
    WHERE ss.carrier = 'FEDEX'
      AND ss.service_code IN (
          'INTERNATIONAL_PRIORITY', 'INTERNATIONAL_ECONOMY',
          'INTERNATIONAL_FIRST', 'EUROPE_FIRST_INTERNATIONAL_PRIORITY')
      AND pp.carrier = 'FEDEX'
      AND pp.carrier_package_code IN (
          'FEDEX_ENVELOPE', 'FEDEX_PAK', 'FEDEX_TUBE',
          'FEDEX_10KG_BOX', 'FEDEX_25KG_BOX')
    ON CONFLICT (service_id, preset_id) DO NOTHING;

    -- FEDEX Ground / Home Delivery: YOUR_PACKAGING only → no rows.

    RAISE NOTICE 'V40 rewrote UPS + FedEx service_package links per authoritative carrier rules';
END $$;
