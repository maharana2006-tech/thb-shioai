-- §4 extraction: country.is_us_territory
-- (QuickShip F15 / Manual F-19 — "Hardcoded US-territory list (BE + FE)").
--
-- The six US-territory codes live as a compile-time
-- Set.of("PR","VI","GU","AS","MP","UM") in UsTerritoryNormalizer. Adding
-- a seventh (unlikely but has happened once in history — UM was added
-- after the original ISO revision) required a redeploy; the FE mirror
-- in multiship-react/src/utils/countries.ts had to be touched too.
--
-- V111 adds a minimal platform-wide country table so the territory
-- membership is DB-editable. CountryPlatformService loads is_us_territory
-- rows on ApplicationReadyEvent and swaps the normalizer's set in place
-- (volatile Set, no caller touches) — Java constant stays as the
-- bootstrap default when the DB table is empty, so there is zero risk
-- during the first boot after this migration.
--
-- Country rows beyond the territories are intentionally not seeded —
-- unseeded codes are implicitly is_us_territory=false which matches the
-- current behaviour. Later extraction items will populate the rest.

DO $$
BEGIN
    IF to_regclass('public.country') IS NULL THEN
        CREATE TABLE country (
            country_code      CHAR(2) PRIMARY KEY,
            name              VARCHAR(120),
            is_us_territory   BOOLEAN NOT NULL DEFAULT FALSE,
            created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
            updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS ix_country_is_us_territory
            ON country (is_us_territory) WHERE is_us_territory = TRUE;
    END IF;

    -- Seed the six US territories. Idempotent — safe to re-run.
    INSERT INTO country (country_code, name, is_us_territory) VALUES
        ('PR', 'Puerto Rico',                  TRUE),
        ('VI', 'US Virgin Islands',            TRUE),
        ('GU', 'Guam',                         TRUE),
        ('AS', 'American Samoa',               TRUE),
        ('MP', 'Northern Mariana Islands',     TRUE),
        ('UM', 'US Minor Outlying Islands',    TRUE)
    ON CONFLICT (country_code) DO UPDATE
        SET is_us_territory = EXCLUDED.is_us_territory,
            name            = EXCLUDED.name,
            updated_at      = NOW();
END $$;
