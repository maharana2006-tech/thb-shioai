-- Sprint 52 — seed service_package compatibility rows for carrier-branded
-- packaging. Covers the FedEx service families where the 900003-class bug
-- (PACKAGINGTYPE.VALIDATION.ERROR) is most likely to hit: envelope/pak/tube
-- are Express-only, One Rate boxes are One Rate-only, 10kg/25kg are
-- international-only. CUSTOM (YOUR_PACKAGING) presets are exempt in
-- PackagingCompatibilityGuard so they don't need seed rows.
--
-- This walks back the ShippingConfigSeeder.java:14 doctrine ("catalog data
-- is admin-populated, never seeded"). Explicit design decision: for pairs
-- that FedEx's own API rejects, the seed is a factual mirror of the
-- carrier's rules, not opinionated business config. UPS / DHL / USPS
-- seeding is left as an ops follow-up because those carriers accept a
-- much wider service×package matrix; the guard will fall through to
-- SERVICE_HAS_NO_LINKED_PACKAGES for services with no rows, which points
-- ops to /settings/shipping-catalog.
--
-- Idempotent + fresh-DB safe: guarded by to_regclass so a fresh Postgres
-- skips the block (Hibernate materialises the tables from @Entity, then
-- this migration re-runs and populates on second boot). Every INSERT
-- uses NOT EXISTS so re-runs on populated tables are no-ops.
-- See docs/flyway-fresh-db-guard-pattern.md.
DO $$
BEGIN
    IF to_regclass('public.service_package') IS NULL
       OR to_regclass('public.shipping_service') IS NULL
       OR to_regclass('public.package_preset') IS NULL THEN
        RAISE NOTICE 'V29 skipped — one of service_package/shipping_service/package_preset missing (fresh DB before first sync)';
        RETURN;
    END IF;

    -- ─── FedEx Express family × envelope / pak / tube ─────────────────────
    -- FedEx accepts FEDEX_ENVELOPE / FEDEX_PAK / FEDEX_TUBE only on Express
    -- services (2Day, Express Saver, Standard/Priority/First Overnight,
    -- and all International services).
    INSERT INTO service_package (service_id, preset_id)
    SELECT s.id, p.id
    FROM shipping_service s
    CROSS JOIN package_preset p
    WHERE s.carrier = 'FEDEX'
      AND s.service_code IN (
          'FEDEX_2_DAY', 'FEDEX_2_DAY_AM', 'FEDEX_EXPRESS_SAVER',
          'STANDARD_OVERNIGHT', 'PRIORITY_OVERNIGHT', 'FIRST_OVERNIGHT',
          'INTERNATIONAL_PRIORITY', 'INTERNATIONAL_ECONOMY',
          'FEDEX_INTERNATIONAL_PRIORITY', 'FEDEX_INTERNATIONAL_ECONOMY',
          'FEDEX_INTERNATIONAL_CONNECT_PLUS'
      )
      AND p.carrier = 'FEDEX'
      AND p.carrier_package_code IN ('FEDEX_ENVELOPE', 'FEDEX_PAK', 'FEDEX_TUBE')
      AND NOT EXISTS (
          SELECT 1 FROM service_package sp
          WHERE sp.service_id = s.id AND sp.preset_id = p.id
      );

    -- ─── FedEx One Rate services × One Rate boxes ─────────────────────────
    -- FedEx One Rate is a flat-rate program: only the four branded boxes
    -- (SMALL / MEDIUM / LARGE / EXTRA_LARGE) are valid, only on the Express
    -- services that participate. Envelope/Pak/Tube also participate but
    -- are seeded by the block above.
    INSERT INTO service_package (service_id, preset_id)
    SELECT s.id, p.id
    FROM shipping_service s
    CROSS JOIN package_preset p
    WHERE s.carrier = 'FEDEX'
      AND s.service_code IN (
          'FEDEX_2_DAY', 'FEDEX_EXPRESS_SAVER',
          'STANDARD_OVERNIGHT', 'PRIORITY_OVERNIGHT', 'FIRST_OVERNIGHT'
      )
      AND p.carrier = 'FEDEX'
      AND p.carrier_package_code IN (
          'FEDEX_SMALL_BOX', 'FEDEX_MEDIUM_BOX',
          'FEDEX_LARGE_BOX', 'FEDEX_EXTRA_LARGE_BOX'
      )
      AND NOT EXISTS (
          SELECT 1 FROM service_package sp
          WHERE sp.service_id = s.id AND sp.preset_id = p.id
      );

    -- ─── FedEx 10kg / 25kg boxes × international services ─────────────────
    -- FEDEX_10KG_BOX and FEDEX_25KG_BOX are international-only per FedEx
    -- API. Available on all international services + Ground (rare) but NOT
    -- on domestic Express or the One Rate flat-rate program.
    INSERT INTO service_package (service_id, preset_id)
    SELECT s.id, p.id
    FROM shipping_service s
    CROSS JOIN package_preset p
    WHERE s.carrier = 'FEDEX'
      AND s.service_code IN (
          'INTERNATIONAL_PRIORITY', 'INTERNATIONAL_ECONOMY',
          'FEDEX_INTERNATIONAL_PRIORITY', 'FEDEX_INTERNATIONAL_ECONOMY',
          'FEDEX_INTERNATIONAL_CONNECT_PLUS',
          'FEDEX_INTERNATIONAL_GROUND'
      )
      AND p.carrier = 'FEDEX'
      AND p.carrier_package_code IN ('FEDEX_10KG_BOX', 'FEDEX_25KG_BOX')
      AND NOT EXISTS (
          SELECT 1 FROM service_package sp
          WHERE sp.service_id = s.id AND sp.preset_id = p.id
      );

    -- Deliberately NOT seeded (guard falls through → operator/admin acts):
    --   FEDEX_GROUND × any FEDEX_* preset — Ground accepts only YOUR_PACKAGING
    --     (handled by CUSTOM short-circuit in the guard). Order 900003's
    --     FEDEX_GROUND + FEDEX_ENVELOPE combo now throws
    --     PACKAGE_NOT_ALLOWED_FOR_SERVICE before reaching FedEx.
    --   UPS / DHL / USPS × anything — deferred; admin populates via
    --     /settings/shipping-catalog. Services with no rows return
    --     SERVICE_HAS_NO_LINKED_PACKAGES which routes to that page.

    RAISE NOTICE 'V29 seeded service_package rows for FedEx Express×envelope/pak/tube, One Rate×One Rate boxes, International×10kg/25kg';
END $$;
