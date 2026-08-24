-- F6-B1 (fallback-audit follow-up) — two related schema additions that unlock
-- the ShipmentDefaultsResolver (F6-B3) without introducing any behavior change
-- on its own. This migration is data-only; no code path depends on either
-- object until F6-B3 lands.
--
-- Change 1: country_currency table + seed of ~250 ISO 3166 → ISO 4217 mappings
--
--   Powers the resolver's currency-fallback chain when a Client hasn't set
--   defaultCurrency explicitly. Precedence: request → customs → Client →
--   country_currency lookup by client's ship-from country → domestic USD /
--   international throw. Seeded from Wikipedia's ISO 4217 country list; 250
--   sovereign + dependent territories with active national currencies (Antarctica,
--   Bouvet Island etc. that share the CCY of the administrating country are
--   assigned to that CCY — best-effort for the resolver's fallback, admins can
--   later override via CRUD if the shipping context needs it).
--
-- Change 2: order_customs.dim_unit column
--
--   Currently OrderCustoms.weightUnit exists but not dimUnit. This asymmetry
--   means a per-shipment override for weight ("this pallet is KG") but not
--   for dimensions ("this pallet is CM"). The resolver reads customs first
--   for both — adding the column brings the two in line. Nullable so existing
--   rows aren't affected; resolver falls through to client / hardcode when null.
--
-- Fresh-DB pattern: guarded by to_regclass so a fresh Postgres (Hibernate
-- creates tables from entity metadata BEFORE Flyway runs V2+) skips this
-- migration cleanly and Hibernate materialises the objects on the next boot.
-- See docs/flyway-fresh-db-guard-pattern.md.

-- ============================================================================
-- Change 1: country_currency table + seed
-- ============================================================================

DO $$
BEGIN
    -- Create the table. On a fresh Postgres, Hibernate materialises this
    -- from the @Entity mapping on the next boot; here we just ensure the
    -- migration is a no-op if the app already booted once and created it.
    CREATE TABLE IF NOT EXISTS country_currency (
        country_code   CHAR(2)     NOT NULL PRIMARY KEY,
        currency_code  CHAR(3)     NOT NULL,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    -- Seed all ~250 ISO 3166-1 alpha-2 → ISO 4217 mappings.
    -- INSERT ... ON CONFLICT DO NOTHING makes the seed idempotent — re-running
    -- the migration on an env that already has data is a no-op per row.
    INSERT INTO country_currency (country_code, currency_code) VALUES
        -- A
        ('AD', 'EUR'), ('AE', 'AED'), ('AF', 'AFN'), ('AG', 'XCD'), ('AI', 'XCD'),
        ('AL', 'ALL'), ('AM', 'AMD'), ('AO', 'AOA'), ('AQ', 'USD'), ('AR', 'ARS'),
        ('AS', 'USD'), ('AT', 'EUR'), ('AU', 'AUD'), ('AW', 'AWG'), ('AX', 'EUR'),
        ('AZ', 'AZN'),
        -- B
        ('BA', 'BAM'), ('BB', 'BBD'), ('BD', 'BDT'), ('BE', 'EUR'), ('BF', 'XOF'),
        ('BG', 'BGN'), ('BH', 'BHD'), ('BI', 'BIF'), ('BJ', 'XOF'), ('BL', 'EUR'),
        ('BM', 'BMD'), ('BN', 'BND'), ('BO', 'BOB'), ('BQ', 'USD'), ('BR', 'BRL'),
        ('BS', 'BSD'), ('BT', 'BTN'), ('BV', 'NOK'), ('BW', 'BWP'), ('BY', 'BYN'),
        ('BZ', 'BZD'),
        -- C
        ('CA', 'CAD'), ('CC', 'AUD'), ('CD', 'CDF'), ('CF', 'XAF'), ('CG', 'XAF'),
        ('CH', 'CHF'), ('CI', 'XOF'), ('CK', 'NZD'), ('CL', 'CLP'), ('CM', 'XAF'),
        ('CN', 'CNY'), ('CO', 'COP'), ('CR', 'CRC'), ('CU', 'CUP'), ('CV', 'CVE'),
        ('CW', 'ANG'), ('CX', 'AUD'), ('CY', 'EUR'), ('CZ', 'CZK'),
        -- D
        ('DE', 'EUR'), ('DJ', 'DJF'), ('DK', 'DKK'), ('DM', 'XCD'), ('DO', 'DOP'),
        ('DZ', 'DZD'),
        -- E
        ('EC', 'USD'), ('EE', 'EUR'), ('EG', 'EGP'), ('EH', 'MAD'), ('ER', 'ERN'),
        ('ES', 'EUR'), ('ET', 'ETB'),
        -- F
        ('FI', 'EUR'), ('FJ', 'FJD'), ('FK', 'FKP'), ('FM', 'USD'), ('FO', 'DKK'),
        ('FR', 'EUR'),
        -- G
        ('GA', 'XAF'), ('GB', 'GBP'), ('GD', 'XCD'), ('GE', 'GEL'), ('GF', 'EUR'),
        ('GG', 'GBP'), ('GH', 'GHS'), ('GI', 'GIP'), ('GL', 'DKK'), ('GM', 'GMD'),
        ('GN', 'GNF'), ('GP', 'EUR'), ('GQ', 'XAF'), ('GR', 'EUR'), ('GS', 'GBP'),
        ('GT', 'GTQ'), ('GU', 'USD'), ('GW', 'XOF'), ('GY', 'GYD'),
        -- H
        ('HK', 'HKD'), ('HM', 'AUD'), ('HN', 'HNL'), ('HR', 'EUR'), ('HT', 'HTG'),
        ('HU', 'HUF'),
        -- I
        ('ID', 'IDR'), ('IE', 'EUR'), ('IL', 'ILS'), ('IM', 'GBP'), ('IN', 'INR'),
        ('IO', 'USD'), ('IQ', 'IQD'), ('IR', 'IRR'), ('IS', 'ISK'), ('IT', 'EUR'),
        -- J
        ('JE', 'GBP'), ('JM', 'JMD'), ('JO', 'JOD'), ('JP', 'JPY'),
        -- K
        ('KE', 'KES'), ('KG', 'KGS'), ('KH', 'KHR'), ('KI', 'AUD'), ('KM', 'KMF'),
        ('KN', 'XCD'), ('KP', 'KPW'), ('KR', 'KRW'), ('KW', 'KWD'), ('KY', 'KYD'),
        ('KZ', 'KZT'),
        -- L
        ('LA', 'LAK'), ('LB', 'LBP'), ('LC', 'XCD'), ('LI', 'CHF'), ('LK', 'LKR'),
        ('LR', 'LRD'), ('LS', 'LSL'), ('LT', 'EUR'), ('LU', 'EUR'), ('LV', 'EUR'),
        ('LY', 'LYD'),
        -- M
        ('MA', 'MAD'), ('MC', 'EUR'), ('MD', 'MDL'), ('ME', 'EUR'), ('MF', 'EUR'),
        ('MG', 'MGA'), ('MH', 'USD'), ('MK', 'MKD'), ('ML', 'XOF'), ('MM', 'MMK'),
        ('MN', 'MNT'), ('MO', 'MOP'), ('MP', 'USD'), ('MQ', 'EUR'), ('MR', 'MRU'),
        ('MS', 'XCD'), ('MT', 'EUR'), ('MU', 'MUR'), ('MV', 'MVR'), ('MW', 'MWK'),
        ('MX', 'MXN'), ('MY', 'MYR'), ('MZ', 'MZN'),
        -- N
        ('NA', 'NAD'), ('NC', 'XPF'), ('NE', 'XOF'), ('NF', 'AUD'), ('NG', 'NGN'),
        ('NI', 'NIO'), ('NL', 'EUR'), ('NO', 'NOK'), ('NP', 'NPR'), ('NR', 'AUD'),
        ('NU', 'NZD'), ('NZ', 'NZD'),
        -- O
        ('OM', 'OMR'),
        -- P
        ('PA', 'PAB'), ('PE', 'PEN'), ('PF', 'XPF'), ('PG', 'PGK'), ('PH', 'PHP'),
        ('PK', 'PKR'), ('PL', 'PLN'), ('PM', 'EUR'), ('PN', 'NZD'), ('PR', 'USD'),
        ('PS', 'ILS'), ('PT', 'EUR'), ('PW', 'USD'), ('PY', 'PYG'),
        -- Q
        ('QA', 'QAR'),
        -- R
        ('RE', 'EUR'), ('RO', 'RON'), ('RS', 'RSD'), ('RU', 'RUB'), ('RW', 'RWF'),
        -- S
        ('SA', 'SAR'), ('SB', 'SBD'), ('SC', 'SCR'), ('SD', 'SDG'), ('SE', 'SEK'),
        ('SG', 'SGD'), ('SH', 'SHP'), ('SI', 'EUR'), ('SJ', 'NOK'), ('SK', 'EUR'),
        ('SL', 'SLE'), ('SM', 'EUR'), ('SN', 'XOF'), ('SO', 'SOS'), ('SR', 'SRD'),
        ('SS', 'SSP'), ('ST', 'STN'), ('SV', 'USD'), ('SX', 'ANG'), ('SY', 'SYP'),
        ('SZ', 'SZL'),
        -- T
        ('TC', 'USD'), ('TD', 'XAF'), ('TF', 'EUR'), ('TG', 'XOF'), ('TH', 'THB'),
        ('TJ', 'TJS'), ('TK', 'NZD'), ('TL', 'USD'), ('TM', 'TMT'), ('TN', 'TND'),
        ('TO', 'TOP'), ('TR', 'TRY'), ('TT', 'TTD'), ('TV', 'AUD'), ('TW', 'TWD'),
        ('TZ', 'TZS'),
        -- U
        ('UA', 'UAH'), ('UG', 'UGX'), ('UM', 'USD'), ('US', 'USD'), ('UY', 'UYU'),
        ('UZ', 'UZS'),
        -- V
        ('VA', 'EUR'), ('VC', 'XCD'), ('VE', 'VES'), ('VG', 'USD'), ('VI', 'USD'),
        ('VN', 'VND'), ('VU', 'VUV'),
        -- W
        ('WF', 'XPF'), ('WS', 'WST'),
        -- Y
        ('YE', 'YER'), ('YT', 'EUR'),
        -- Z
        ('ZA', 'ZAR'), ('ZM', 'ZMW'), ('ZW', 'ZWG')
    ON CONFLICT (country_code) DO NOTHING;
END $$;

-- ============================================================================
-- Change 2: order_customs.dim_unit column
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.order_customs') IS NOT NULL THEN
        -- Nullable so existing rows aren't affected. Resolver reads customs.dim_unit
        -- first; falls through to Client.defaultDimUnit → "IN" hardcode when null.
        -- Length 4 mirrors the existing weight_unit column shape (accommodates
        -- "IN" and "CM" plus any future 3-4 char unit code).
        ALTER TABLE order_customs
            ADD COLUMN IF NOT EXISTS dim_unit VARCHAR(4);
    END IF;
END $$;
