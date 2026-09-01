-- Moves each carrier connector's hardcoded published-packaging list
-- (UpsConnector/FedExConnector/StampsConnector/DhlConnector#listPackages)
-- out of Java and into an ops-editable reference table, mirroring the
-- carrier_shipping_limit pattern (ops can correct/extend without a code
-- deploy). This is a *reference catalog*, not package_preset — the
-- ShippingConfigSeeder.java:12-20 doctrine ("package presets come
-- exclusively from carrier packaging sync, nothing else is seeded") is
-- unchanged: package_preset rows are still written only by
-- syncPackagesFromCarrier, which now reads its offerings from this table
-- instead of an in-code List.of(...).
--
-- us_domestic_only mirrors the FedEx One Rate boxes' pre-existing
-- "US or PR origin" gate (FedExConnector originally checked
-- `us = "US".equals(o) || "PR".equals(o)`); connectors apply the same
-- US-or-PR check against this flag.
--
-- Idempotent + fresh-DB safe (see docs/flyway-fresh-db-guard-pattern.md):
-- guarded by to_regclass, every INSERT uses NOT EXISTS.
CREATE TABLE IF NOT EXISTS carrier_package_catalog (
    id                 BIGSERIAL PRIMARY KEY,
    carrier_code       VARCHAR(20)   NOT NULL,
    code               VARCHAR(40)   NOT NULL,
    name               VARCHAR(120)  NOT NULL,
    length             NUMERIC(8,2),
    width              NUMERIC(8,2),
    height             NUMERIC(8,2),
    max_weight         NUMERIC(8,2),
    flat_rate          BOOLEAN       NOT NULL DEFAULT FALSE,
    scope              VARCHAR(20)   NOT NULL DEFAULT 'BOTH',
    us_domestic_only   BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order         INTEGER       NOT NULL DEFAULT 100,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_carrier_package_catalog_key UNIQUE (carrier_code, code)
);

DO $$
BEGIN
    IF to_regclass('public.carrier_package_catalog') IS NULL THEN
        RAISE NOTICE 'V32 skipped — carrier_package_catalog missing (fresh DB before first boot)';
        RETURN;
    END IF;

    -- ─── UPS — published, static catalogue (same set every origin) ───────
    INSERT INTO carrier_package_catalog (carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, sort_order)
    SELECT * FROM (VALUES
        ('UPS', '01', 'UPS Letter', 12.5, 9.5, 0.5, 1, FALSE, 'BOTH', 10),
        ('UPS', '04', 'UPS Express Pak', 16, 12.75, 2, 3, FALSE, 'BOTH', 20),
        ('UPS', '03', 'UPS Tube', 38, 6, 6, NULL, FALSE, 'BOTH', 30),
        ('UPS', '2a', 'UPS Small Express Box', 13, 11, 2, NULL, FALSE, 'BOTH', 40),
        ('UPS', '2b', 'UPS Medium Express Box', 15, 11, 3, NULL, FALSE, 'BOTH', 50),
        ('UPS', '2c', 'UPS Large Express Box', 18, 13, 3, NULL, FALSE, 'BOTH', 60),
        ('UPS', '25', 'UPS 10KG Box', 16.5, 13.25, 10.75, 22, TRUE, 'INTERNATIONAL', 70),
        ('UPS', '24', 'UPS 25KG Box', 16.5, 13.25, 10.75, 55, TRUE, 'INTERNATIONAL', 80)
    ) AS v(carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, sort_order)
    WHERE NOT EXISTS (
        SELECT 1 FROM carrier_package_catalog c WHERE c.carrier_code = v.carrier_code AND c.code = v.code
    );

    -- ─── FedEx — base list (any origin) + US/PR-only One Rate boxes ──────
    INSERT INTO carrier_package_catalog (carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, us_domestic_only, sort_order)
    SELECT * FROM (VALUES
        ('FEDEX', 'FEDEX_ENVELOPE', 'FedEx Envelope', 12.5, 9.5, 0.5, 1, FALSE, 'BOTH', FALSE, 10),
        ('FEDEX', 'FEDEX_PAK', 'FedEx Pak', 15.5, 12, 1.5, 3, FALSE, 'BOTH', FALSE, 20),
        ('FEDEX', 'FEDEX_TUBE', 'FedEx Tube', 38, 6, 6, NULL, FALSE, 'BOTH', FALSE, 30),
        ('FEDEX', 'FEDEX_10KG_BOX', 'FedEx 10kg Box', 15.81, 12.94, 10.19, 22, TRUE, 'INTERNATIONAL', FALSE, 40),
        ('FEDEX', 'FEDEX_25KG_BOX', 'FedEx 25kg Box', 21.56, 16.56, 13.19, 55, TRUE, 'INTERNATIONAL', FALSE, 50),
        ('FEDEX', 'FEDEX_SMALL_BOX', 'FedEx Small Box (One Rate)', 12.375, 10.875, 1.5, 50, TRUE, 'DOMESTIC', TRUE, 60),
        ('FEDEX', 'FEDEX_MEDIUM_BOX', 'FedEx Medium Box (One Rate)', 13.25, 11.5, 2.375, 50, TRUE, 'DOMESTIC', TRUE, 70),
        ('FEDEX', 'FEDEX_LARGE_BOX', 'FedEx Large Box (One Rate)', 17.875, 12.375, 3, 50, TRUE, 'DOMESTIC', TRUE, 80),
        ('FEDEX', 'FEDEX_EXTRA_LARGE_BOX', 'FedEx Extra Large Box (One Rate)', 11.875, 11, 10.75, 50, TRUE, 'DOMESTIC', TRUE, 90)
    ) AS v(carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, us_domestic_only, sort_order)
    WHERE NOT EXISTS (
        SELECT 1 FROM carrier_package_catalog c WHERE c.carrier_code = v.carrier_code AND c.code = v.code
    );

    -- ─── USPS (Stamps) — US-domestic Flat Rate packaging ─────────────────
    INSERT INTO carrier_package_catalog (carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, sort_order)
    SELECT * FROM (VALUES
        ('USPS', 'FLAT_RATE_ENVELOPE', 'USPS Flat Rate Envelope', 12.5, 9.5, 0.5, 70, TRUE, 'DOMESTIC', 10),
        ('USPS', 'SM_FLAT_RATE_BOX', 'USPS Small Flat Rate Box', 8.69, 5.44, 1.75, 70, TRUE, 'DOMESTIC', 20),
        ('USPS', 'MD_FLAT_RATE_BOX', 'USPS Medium Flat Rate Box', 11.25, 8.75, 6, 70, TRUE, 'DOMESTIC', 30),
        ('USPS', 'LG_FLAT_RATE_BOX', 'USPS Large Flat Rate Box', 12.25, 12, 6, 70, TRUE, 'DOMESTIC', 40)
    ) AS v(carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, sort_order)
    WHERE NOT EXISTS (
        SELECT 1 FROM carrier_package_catalog c WHERE c.carrier_code = v.carrier_code AND c.code = v.code
    );

    -- ─── DHL Express — envelope + box lineup ─────────────────────────────
    INSERT INTO carrier_package_catalog (carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, sort_order)
    SELECT * FROM (VALUES
        ('DHL', '2BP', 'DHL Express Envelope', 32.5, 22.5, 2.5, 1, FALSE, 'BOTH', 10),
        ('DHL', '2BX', 'DHL Express Box (Small)', 33.7, 18.2, 10, 15, FALSE, 'BOTH', 20),
        ('DHL', '3BX', 'DHL Express Box (Medium)', 33.7, 32, 18.2, 25, FALSE, 'BOTH', 30),
        ('DHL', '4BX', 'DHL Express Box (Large)', 33.7, 32.2, 35, 31, FALSE, 'BOTH', 40)
    ) AS v(carrier_code, code, name, length, width, height, max_weight, flat_rate, scope, sort_order)
    WHERE NOT EXISTS (
        SELECT 1 FROM carrier_package_catalog c WHERE c.carrier_code = v.carrier_code AND c.code = v.code
    );

    RAISE NOTICE 'V32 seeded carrier_package_catalog (UPS/FedEx/USPS/DHL published packaging reference data)';
END $$;
