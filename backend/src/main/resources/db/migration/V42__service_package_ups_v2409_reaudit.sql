-- Full re-audit of UPS service_package links against UPS Ship API
-- v2409 authoritative packaging-service compatibility. V40 seeded
-- the matrix from older UPS docs where Worldwide Expedited (08)
-- accepted Pak / 10KG Box / 25KG Box — v2409 restricts 08 to CSP
-- only (all branded packaging removed from Expedited to align with
-- Ground service tier). Operator hit 121500 on 08 + 25KG Box
-- combination that V40 wrongly permitted.
--
-- Domestic Air (01/02): Letter, CSP, Tube, Pak, Sm/Md/Lg Express Box
--   (10KG/25KG boxes are INTL-only; domestic Express services don't
--   accept them per UPS v2409.)
-- Domestic Ground / Ground-hybrid (03/11/12): CSP only.
--   3 Day Select is UPS Ground network, not an air service.
-- International Express (07/54/65): full branded packaging matrix
--   incl. 10KG / 25KG heavy boxes.
-- International Expedited (08): CSP only per v2409.
--   Down-tiered from Express — no branded packaging permitted.
--
-- CUSTOM presets (kind='CUSTOM') remain implicit-allowed via
-- PackagingCompatibilityGuard's kind='CUSTOM' short-circuit
-- (CarrierServiceImpl.java:1161-1167).
--
-- Existing orders (per user answer 2026-09-06) are LEFT AS-IS —
-- next FE edit will pick up the new picker restriction; if the
-- operator tries to re-generate a stuck (08 + branded) order, the
-- compatibility guard blocks with a clean 122 error. No silent
-- packaging mutation on saved orders.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: DELETE + INSERT
-- pattern rewrites the UPS subset each run.
DO $$
BEGIN
    IF to_regclass('public.service_package') IS NULL
       OR to_regclass('public.shipping_service') IS NULL
       OR to_regclass('public.package_preset') IS NULL THEN
        RAISE NOTICE 'V42 skipped — required tables missing (fresh DB before first sync)';
        RETURN;
    END IF;

    DELETE FROM service_package sp
    USING shipping_service ss
    WHERE sp.service_id = ss.id
      AND ss.carrier = 'UPS';

    -- UPS DOMESTIC AIR: Next Day Air (01) + 2nd Day Air (02)
    INSERT INTO service_package (service_id, preset_id)
    SELECT ss.id, pp.id
    FROM shipping_service ss
    CROSS JOIN package_preset pp
    WHERE ss.carrier = 'UPS'
      AND ss.service_code IN ('01', '02')
      AND pp.carrier = 'UPS'
      AND pp.carrier_package_code IN ('01', '03', '04', '2a', '2b', '2c')
    ON CONFLICT (service_id, preset_id) DO NOTHING;

    -- UPS INTL WORLDWIDE EXPRESS variants: 07 / 54 / 65
    -- Full branded matrix incl. 10KG (25) + 25KG (24) heavy boxes.
    INSERT INTO service_package (service_id, preset_id)
    SELECT ss.id, pp.id
    FROM shipping_service ss
    CROSS JOIN package_preset pp
    WHERE ss.carrier = 'UPS'
      AND ss.service_code IN ('07', '54', '65')
      AND pp.carrier = 'UPS'
      AND pp.carrier_package_code IN ('01', '03', '04', '2a', '2b', '2c', '24', '25')
    ON CONFLICT (service_id, preset_id) DO NOTHING;

    -- UPS Ground (03) + 3 Day Select (12) + Standard (11) +
    -- Worldwide Expedited (08): CSP-only per v2409. Deliberately no
    -- INSERTs — operator picks CUSTOM ("Your boxes") or falls back to
    -- YOUR_PACKAGING.

    RAISE NOTICE 'V42 re-audited UPS service_package links against Ship API v2409 (08 now CSP-only)';
END $$;
