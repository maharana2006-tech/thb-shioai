-- §4 extraction trio — reason_for_export + iso_currency + country_region.
--
-- All three move data that today lives as compile-time literals into
-- DB tables so ops can add a new reason / currency / region without a
-- redeploy. Behaviour-change is zero in this commit: the three
-- *PlatformService loaders swap in the DB-driven set at
-- ApplicationReadyEvent; the Java constants + FE arrays stay as
-- bootstrap defaults until FE is next touched.

DO $$
BEGIN
    -- (1) reason_for_export (Manual F-10) — SHIPPING_PURPOSES in
    -- multiship-react/src/utils/customsOptions.ts. 8 seed rows.
    IF to_regclass('public.reason_for_export') IS NULL THEN
        CREATE TABLE reason_for_export (
            code       VARCHAR(32) PRIMARY KEY,
            label      VARCHAR(120) NOT NULL,
            sort_order INTEGER DEFAULT 0,
            created_at TIMESTAMP NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMP NOT NULL DEFAULT NOW()
        );
    END IF;
    INSERT INTO reason_for_export (code, label, sort_order) VALUES
        ('SALE',              'Sale',              10),
        ('GIFT',              'Gift',              20),
        ('SAMPLE',            'Sample',            30),
        ('REPAIR_AND_RETURN', 'Repair and return', 40),
        ('DOCUMENTS',         'Documents',         50),
        ('MERCHANDISE',       'Merchandise',       60),
        ('PERSONAL_USE',      'Personal use',      70),
        ('RETURN',            'Return',            80)
    ON CONFLICT (code) DO UPDATE
        SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order, updated_at = NOW();

    -- (2) iso_currency (Manual F-08) — the NewShipmentPage CURRENCIES
    -- array is 10 codes; AED was added manually last time. Seed the ten
    -- in use today; further ISO-4217 fill is a follow-up migration.
    IF to_regclass('public.iso_currency') IS NULL THEN
        CREATE TABLE iso_currency (
            code       CHAR(3) PRIMARY KEY,
            name       VARCHAR(80),
            created_at TIMESTAMP NOT NULL DEFAULT NOW()
        );
    END IF;
    INSERT INTO iso_currency (code, name) VALUES
        ('USD', 'US Dollar'),
        ('EUR', 'Euro'),
        ('GBP', 'Pound Sterling'),
        ('CAD', 'Canadian Dollar'),
        ('INR', 'Indian Rupee'),
        ('AUD', 'Australian Dollar'),
        ('SGD', 'Singapore Dollar'),
        ('JPY', 'Japanese Yen'),
        ('CNY', 'Chinese Yuan'),
        ('AED', 'UAE Dirham')
    ON CONFLICT (code) DO NOTHING;

    -- (3) country_region (Jobs H-2) — CountryRegions.java hardcodes 243
    -- country→region assignments. region(code,label) + country_region FK
    -- model lets ops reclassify a country or add a new region row.
    IF to_regclass('public.region') IS NULL THEN
        CREATE TABLE region (
            code       VARCHAR(32) PRIMARY KEY,
            label      VARCHAR(80) NOT NULL,
            sort_order INTEGER DEFAULT 0
        );
    END IF;
    INSERT INTO region (code, label, sort_order) VALUES
        ('NORTH_AMERICA', 'North America', 10),
        ('EUROPE',        'Europe',        20),
        ('MIDDLE_EAST',   'Middle East',   30),
        ('ASIA',          'Asia',          40),
        ('OCEANIA',       'Oceania',       50),
        ('SOUTH_AMERICA', 'South America', 60),
        ('AFRICA',        'Africa',        70),
        ('OTHER',         'Other',         80)
    ON CONFLICT (code) DO UPDATE
        SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order;

    IF to_regclass('public.country_region') IS NULL THEN
        CREATE TABLE country_region (
            country_code CHAR(2) PRIMARY KEY,
            region_code  VARCHAR(32) NOT NULL REFERENCES region(code) ON DELETE RESTRICT
        );
        CREATE INDEX IF NOT EXISTS ix_country_region_region ON country_region (region_code);
    END IF;
    -- Mirrors backend/src/main/java/.../util/CountryRegions.java and
    -- multiship-react/src/utils/countries.ts. On conflict update to keep
    -- re-runs idempotent.
    INSERT INTO country_region (country_code, region_code) VALUES
        ('US','NORTH_AMERICA'),('CA','NORTH_AMERICA'),('MX','NORTH_AMERICA'),
        ('AG','NORTH_AMERICA'),('AI','NORTH_AMERICA'),('AW','NORTH_AMERICA'),('BB','NORTH_AMERICA'),
        ('BL','NORTH_AMERICA'),('BM','NORTH_AMERICA'),('BQ','NORTH_AMERICA'),('BS','NORTH_AMERICA'),
        ('BZ','NORTH_AMERICA'),('CR','NORTH_AMERICA'),('CU','NORTH_AMERICA'),('CW','NORTH_AMERICA'),
        ('DM','NORTH_AMERICA'),('DO','NORTH_AMERICA'),('GD','NORTH_AMERICA'),('GL','NORTH_AMERICA'),
        ('GP','NORTH_AMERICA'),('GT','NORTH_AMERICA'),('HN','NORTH_AMERICA'),('HT','NORTH_AMERICA'),
        ('JM','NORTH_AMERICA'),('KN','NORTH_AMERICA'),('KY','NORTH_AMERICA'),('LC','NORTH_AMERICA'),
        ('MF','NORTH_AMERICA'),('MQ','NORTH_AMERICA'),('MS','NORTH_AMERICA'),('NI','NORTH_AMERICA'),
        ('PA','NORTH_AMERICA'),('PM','NORTH_AMERICA'),('PR','NORTH_AMERICA'),('SV','NORTH_AMERICA'),
        ('SX','NORTH_AMERICA'),('TC','NORTH_AMERICA'),('TT','NORTH_AMERICA'),('VC','NORTH_AMERICA'),
        ('VG','NORTH_AMERICA'),('VI','NORTH_AMERICA'),
        ('GB','EUROPE'),('IE','EUROPE'),('DE','EUROPE'),('FR','EUROPE'),('ES','EUROPE'),('IT','EUROPE'),
        ('NL','EUROPE'),('BE','EUROPE'),('AD','EUROPE'),('AL','EUROPE'),('AT','EUROPE'),('AX','EUROPE'),
        ('BA','EUROPE'),('BG','EUROPE'),('BY','EUROPE'),('CH','EUROPE'),('CY','EUROPE'),('CZ','EUROPE'),
        ('DK','EUROPE'),('EE','EUROPE'),('FI','EUROPE'),('FO','EUROPE'),('GG','EUROPE'),('GI','EUROPE'),
        ('GR','EUROPE'),('HR','EUROPE'),('HU','EUROPE'),('IM','EUROPE'),('IS','EUROPE'),('JE','EUROPE'),
        ('LI','EUROPE'),('LT','EUROPE'),('LU','EUROPE'),('LV','EUROPE'),('MC','EUROPE'),('MD','EUROPE'),
        ('ME','EUROPE'),('MK','EUROPE'),('MT','EUROPE'),('NO','EUROPE'),('PL','EUROPE'),('PT','EUROPE'),
        ('RO','EUROPE'),('RS','EUROPE'),('RU','EUROPE'),('SE','EUROPE'),('SI','EUROPE'),('SJ','EUROPE'),
        ('SK','EUROPE'),('SM','EUROPE'),('UA','EUROPE'),('VA','EUROPE'),('XK','EUROPE'),
        ('AE','MIDDLE_EAST'),('BH','MIDDLE_EAST'),('IL','MIDDLE_EAST'),('IQ','MIDDLE_EAST'),
        ('IR','MIDDLE_EAST'),('JO','MIDDLE_EAST'),('KW','MIDDLE_EAST'),('LB','MIDDLE_EAST'),
        ('OM','MIDDLE_EAST'),('PS','MIDDLE_EAST'),('QA','MIDDLE_EAST'),('SA','MIDDLE_EAST'),
        ('SY','MIDDLE_EAST'),('TR','MIDDLE_EAST'),('YE','MIDDLE_EAST'),
        ('JP','ASIA'),('CN','ASIA'),('HK','ASIA'),('SG','ASIA'),('KR','ASIA'),('IN','ASIA'),
        ('AF','ASIA'),('AM','ASIA'),('AZ','ASIA'),('BD','ASIA'),('BN','ASIA'),('BT','ASIA'),
        ('GE','ASIA'),('ID','ASIA'),('KG','ASIA'),('KH','ASIA'),('KP','ASIA'),('KZ','ASIA'),
        ('LA','ASIA'),('LK','ASIA'),('MM','ASIA'),('MN','ASIA'),('MO','ASIA'),('MV','ASIA'),
        ('MY','ASIA'),('NP','ASIA'),('PH','ASIA'),('PK','ASIA'),('TH','ASIA'),('TJ','ASIA'),
        ('TL','ASIA'),('TM','ASIA'),('TW','ASIA'),('UZ','ASIA'),('VN','ASIA'),
        ('AU','OCEANIA'),('NZ','OCEANIA'),('AS','OCEANIA'),('CK','OCEANIA'),('FJ','OCEANIA'),
        ('FM','OCEANIA'),('GU','OCEANIA'),('KI','OCEANIA'),('MH','OCEANIA'),('MP','OCEANIA'),
        ('NC','OCEANIA'),('NR','OCEANIA'),('NU','OCEANIA'),('NF','OCEANIA'),('PF','OCEANIA'),
        ('PG','OCEANIA'),('PW','OCEANIA'),('SB','OCEANIA'),('TK','OCEANIA'),('TO','OCEANIA'),
        ('TV','OCEANIA'),('VU','OCEANIA'),('WF','OCEANIA'),('WS','OCEANIA'),
        ('AR','SOUTH_AMERICA'),('BO','SOUTH_AMERICA'),('BR','SOUTH_AMERICA'),('CL','SOUTH_AMERICA'),
        ('CO','SOUTH_AMERICA'),('EC','SOUTH_AMERICA'),('FK','SOUTH_AMERICA'),('GF','SOUTH_AMERICA'),
        ('GY','SOUTH_AMERICA'),('PE','SOUTH_AMERICA'),('PY','SOUTH_AMERICA'),('SR','SOUTH_AMERICA'),
        ('UY','SOUTH_AMERICA'),('VE','SOUTH_AMERICA'),
        ('ZA','AFRICA'),('AO','AFRICA'),('BF','AFRICA'),('BI','AFRICA'),('BJ','AFRICA'),('BW','AFRICA'),
        ('CD','AFRICA'),('CF','AFRICA'),('CG','AFRICA'),('CI','AFRICA'),('CM','AFRICA'),('CV','AFRICA'),
        ('DJ','AFRICA'),('DZ','AFRICA'),('EG','AFRICA'),('EH','AFRICA'),('ER','AFRICA'),('ET','AFRICA'),
        ('GA','AFRICA'),('GH','AFRICA'),('GM','AFRICA'),('GN','AFRICA'),('GQ','AFRICA'),('GW','AFRICA'),
        ('KE','AFRICA'),('KM','AFRICA'),('LR','AFRICA'),('LS','AFRICA'),('LY','AFRICA'),('MA','AFRICA'),
        ('MG','AFRICA'),('ML','AFRICA'),('MR','AFRICA'),('MU','AFRICA'),('MW','AFRICA'),('MZ','AFRICA'),
        ('NA','AFRICA'),('NE','AFRICA'),('NG','AFRICA'),('RE','AFRICA'),('RW','AFRICA'),('SC','AFRICA'),
        ('SD','AFRICA'),('SH','AFRICA'),('SL','AFRICA'),('SN','AFRICA'),('SO','AFRICA'),('SS','AFRICA'),
        ('ST','AFRICA'),('SZ','AFRICA'),('TD','AFRICA'),('TG','AFRICA'),('TN','AFRICA'),('TZ','AFRICA'),
        ('UG','AFRICA'),('YT','AFRICA'),('ZM','AFRICA'),('ZW','AFRICA'),
        ('AQ','OTHER'),('BV','OTHER'),('CC','OTHER'),('CX','OTHER'),('GS','OTHER'),('HM','OTHER'),
        ('IO','OTHER'),('PN','OTHER'),('TF','OTHER'),('UM','OTHER')
    ON CONFLICT (country_code) DO UPDATE SET region_code = EXCLUDED.region_code;
END $$;
