-- FDX-G1 (FedEx-audit follow-up) — per-service Express-vs-Ground flag.
--
-- Adds a boolean is_express column to shipping_service so ManifestServiceImpl
-- can split closeOutDay calls by driver fleet (FedEx FDXE vs FDXG, UPS 007
-- vs 003). Pre-FDX-G, every FedEx manifest hardcoded carrierCode="FDXG"
-- which silently manifested Express labels as Ground; the Ground driver
-- collected the labels but the carrier's Express manifest was empty.
--
-- Backfill logic (SEEDED services + any CARRIER_SYNC'd rows in existing DBs):
--   · UPS   — anything NOT containing GROUND or matching 03/003 → Express
--   · FEDEX — anything NOT containing GROUND / HOME_DELIVERY → Express
--   · DHL   — always Express (DHL Express is a single-fleet product)
--   · USPS  — always false (SWSIM has no fleet-split; closeOutDay is
--             carrier-code-agnostic for USPS/Stamps)
--
-- Fresh-DB pattern: guarded by to_regclass so a fresh Postgres skips this
-- migration and Hibernate materialises the column from the @Entity on
-- next boot. See docs/flyway-fresh-db-guard-pattern.md.
--
-- No code path reads this column until FDX-G2 (manifest split) lands, so
-- this migration is zero-risk on its own — is_express defaults to false
-- and existing closeOutDay callers keep using the pre-fix carrierCode.
DO $$
BEGIN
    IF to_regclass('public.shipping_service') IS NOT NULL THEN
        -- Not-null with default false so existing rows read as Ground —
        -- FDX-G2's classify path falls through to the pre-fix "everything is
        -- Ground" behavior when the backfill misses (e.g. a new carrier
        -- added between the column creation and the backfill running).
        ALTER TABLE shipping_service
            ADD COLUMN IF NOT EXISTS is_express BOOLEAN NOT NULL DEFAULT false;

        -- FedEx Express portfolio — anything that isn't Ground or Home
        -- Delivery collects on the Express fleet. Includes 2Day, Overnight
        -- (Standard + Priority), Express Saver, International Priority/
        -- Economy/First. The ILIKE test is intentional — future FedEx
        -- services should be classified explicitly if they're not
        -- caught by the exclusion list.
        UPDATE shipping_service
        SET is_express = true
        WHERE UPPER(carrier) = 'FEDEX'
          AND UPPER(service_code) NOT ILIKE '%GROUND%'
          AND UPPER(service_code) NOT ILIKE '%HOME_DELIVERY%';

        -- UPS Ground vs Air fleet split. UPS service codes: 03 = Ground,
        -- 07 = Worldwide Express, 08/54/65 = Standard/Expedited/Saver,
        -- 11 = UPS Standard, 12/13 = 3 Day Select / Next Day Air Saver,
        -- 14 = Next Day Air Early, 59 = 2nd Day Air A.M., 96 = UPS Worldwide
        -- Express Freight. Ground = code 03 (or "003" zero-padded); rest is
        -- Air / Express fleet.
        UPDATE shipping_service
        SET is_express = true
        WHERE UPPER(carrier) = 'UPS'
          AND UPPER(service_code) NOT IN ('03', '003')
          AND UPPER(service_code) NOT ILIKE '%GROUND%';

        -- DHL Express is a single-fleet product; all DHL services collect
        -- on the same Express driver.
        UPDATE shipping_service
        SET is_express = true
        WHERE UPPER(carrier) = 'DHL';

        -- USPS/Stamps has no per-request fleet split at the SWSIM manifest
        -- level. Leave every USPS row as false so ManifestServiceImpl skips
        -- the Express branch entirely for USPS.
        --
        -- (No explicit UPDATE — the DEFAULT false already covers it.)
    END IF;
END $$;
