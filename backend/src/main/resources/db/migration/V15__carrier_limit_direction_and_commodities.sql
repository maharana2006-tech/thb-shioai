-- Sprint 52 line-item caps — extend carrier_shipping_limit with:
--   * direction        (FORWARD | RETURN | NULL)  — some carriers cap
--                       returns lower than forwards (UPS: 20 return vs
--                       200 forward). Null = applies to either direction.
--   * max_commodities  INT NULL                   — customs-line cap per
--                       carrier. Nullable so existing rows keep working
--                       during rollout.
--
-- Also corrects the historical UPS row (was 20/BOTH, seeded as a
-- conservative default across every UPS call) — 20 is UPS's *return*
-- MPS cap; forward MPS is 200. Splitting a 21-package forward shipment
-- into 2 sub-calls when UPS accepts it in one call is expensive
-- (2× rating, 2× carrier RTT, 2× tracking rows), so we set the existing
-- row to RETURN and insert a new FORWARD row at the true 200 cap.
--
-- Backfills max_commodities per carrier at pragmatic ceilings:
--   FedEx / DHL / Stamps → 999 (generous headroom, matches carrier
--   spec-sheet MAX). UPS   → 50 (UPS Paperless Invoice API caps ProductList
--   at 50 lines). Null-service UPS rows inherit the same cap.
--
-- Two new UPS single-piece rows: MAIL_INNOVATIONS and SIMPLE_RATE are
-- explicitly single-package services — carrier will reject anything > 1.
--
-- TODO(sprint52): a US → PR (Puerto Rico) return needs a special-cased
-- limit that depends on origin/dest inspection at request time — cannot
-- be seeded here. Handle in CarrierLimitService when direction=RETURN
-- and dest country=PR + origin country=US.
--
-- Follows the fresh-DB Flyway pattern (docs/flyway-fresh-db-guard-pattern.md):
-- guarded by to_regclass so a fresh Postgres with no table yet skips
-- cleanly and lets Hibernate materialise the table on the next boot.
DO $$
BEGIN
    IF to_regclass('public.carrier_shipping_limit') IS NOT NULL THEN
        ALTER TABLE carrier_shipping_limit ADD COLUMN IF NOT EXISTS direction VARCHAR(16);
        ALTER TABLE carrier_shipping_limit ADD COLUMN IF NOT EXISTS max_commodities INT;

        -- Backfill max_commodities on existing rows so post-migration
        -- lookups never see NULL for a live carrier.
        UPDATE carrier_shipping_limit SET max_commodities = 999
            WHERE carrier_code = 'FEDEX'  AND max_commodities IS NULL;
        UPDATE carrier_shipping_limit SET max_commodities = 50
            WHERE carrier_code = 'UPS'    AND max_commodities IS NULL;
        UPDATE carrier_shipping_limit SET max_commodities = 999
            WHERE carrier_code = 'DHL'    AND max_commodities IS NULL;
        UPDATE carrier_shipping_limit SET max_commodities = 999
            WHERE carrier_code = 'STAMPS' AND max_commodities IS NULL;

        -- Existing "UPS / null service / BOTH / 20 pkgs" row is actually
        -- the RETURN cap — tag it explicitly so the new FORWARD row can
        -- coexist without colliding on the (carrier, service, scope,
        -- effective_from) uniqueness constraint.
        UPDATE carrier_shipping_limit
           SET direction = 'RETURN',
               notes     = 'UPS RETURN MPS: 20 pkgs (retagged in Sprint 52; forward = 200).'
         WHERE carrier_code = 'UPS'
           AND service_code IS NULL
           AND scope = 'BOTH'
           AND max_packages = 20
           AND direction IS NULL;

        -- Seed the true UPS forward cap and the two single-piece services.
        -- Use a slightly later effective_from so the unique constraint
        -- (carrier, service, scope, effective_from) never collides with
        -- the existing null-service BOTH row above.
        INSERT INTO carrier_shipping_limit
            (carrier_code, service_code, scope, direction, max_packages,
             max_commodities, max_total_weight_lb, free_declared_value,
             effective_from, active, notes)
        SELECT 'UPS', NULL, 'BOTH', 'FORWARD', 200, 50, 30000, 100,
               NOW(), TRUE,
               'UPS Ship API forward MPS: 200 pkgs (corrected in Sprint 52 from prior 20 cap).'
         WHERE NOT EXISTS (
             SELECT 1 FROM carrier_shipping_limit
              WHERE carrier_code = 'UPS' AND service_code IS NULL
                AND scope = 'BOTH' AND direction = 'FORWARD');

        INSERT INTO carrier_shipping_limit
            (carrier_code, service_code, scope, direction, max_packages,
             max_commodities, max_total_weight_lb, free_declared_value,
             effective_from, active, notes)
        SELECT 'UPS', 'MAIL_INNOVATIONS', 'BOTH', NULL, 1, 50, 70, 100,
               NOW(), TRUE,
               'UPS Mail Innovations: single-piece only.'
         WHERE NOT EXISTS (
             SELECT 1 FROM carrier_shipping_limit
              WHERE carrier_code = 'UPS' AND service_code = 'MAIL_INNOVATIONS');

        INSERT INTO carrier_shipping_limit
            (carrier_code, service_code, scope, direction, max_packages,
             max_commodities, max_total_weight_lb, free_declared_value,
             effective_from, active, notes)
        SELECT 'UPS', 'SIMPLE_RATE', 'BOTH', NULL, 1, 50, 50, 100,
               NOW(), TRUE,
               'UPS Simple Rate: single-piece flat-rate service.'
         WHERE NOT EXISTS (
             SELECT 1 FROM carrier_shipping_limit
              WHERE carrier_code = 'UPS' AND service_code = 'SIMPLE_RATE');
    END IF;
END $$;
