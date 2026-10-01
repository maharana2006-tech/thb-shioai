-- §4 extraction: carriers platform table (Auth Gap-6-A).
--
-- shioai has 5 hardcoded @Component connectors — enabling / disabling a
-- carrier requires a code deploy. ShipX has one SPX_tblShipCarrier row
-- per carrier with Mode (Test/Live), isEnabled, display name — admins
-- toggle without a release.
--
-- V112 adds the platform-wide carriers table so admins can:
--   - disable a carrier org-wide (enabled=FALSE blocks every dispatch)
--   - flip a carrier between TEST and LIVE org-wide (reserved; per-account
--     SANDBOX/PRODUCTION on carrier_account_ref stays authoritative today)
--   - group USPS-family carriers (STAMPS_COM + USPS_DIRECT roll up to USPS)
--     for future non-NDS consumers to pivot off
--
-- The `enabled` gate is enforced at CarrierServiceImpl's main label-dispatch
-- entry point (R-G -> this commit); future audit items will swap more call
-- sites to read `family` and `mode` instead of their inline switch
-- statements.
--
-- Seed covers the six currently-connected carriers (FedEx + UPS + USPS +
-- DHL + STAMPS_COM + USPS_DIRECT), all enabled=TRUE mode=LIVE — zero
-- behaviour change until an admin flips a row.

DO $$
BEGIN
    IF to_regclass('public.carriers') IS NULL THEN
        CREATE TABLE carriers (
            carrier_code      VARCHAR(32) PRIMARY KEY,
            display_name      VARCHAR(80),
            enabled           BOOLEAN NOT NULL DEFAULT TRUE,
            -- 'LIVE' hits the carrier's production API; 'TEST' is reserved
            -- for a future org-wide sandbox switch (per-account env on
            -- carrier_account_ref is authoritative until then).
            mode              VARCHAR(8) NOT NULL DEFAULT 'LIVE',
            -- USPS | FEDEX | UPS | DHL — rolls up STAMPS_COM + USPS_DIRECT
            -- under USPS so non-NDS consumers (bulk, routing, etc.) can
            -- treat them as one family without a hardcoded switch.
            family            VARCHAR(16),
            created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
            updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
            CONSTRAINT ck_carriers_mode CHECK (mode IN ('LIVE','TEST'))
        );
        CREATE INDEX IF NOT EXISTS ix_carriers_enabled
            ON carriers (enabled) WHERE enabled = TRUE;
        CREATE INDEX IF NOT EXISTS ix_carriers_family ON carriers (family);
    END IF;

    INSERT INTO carriers (carrier_code, display_name, enabled, mode, family) VALUES
        ('FEDEX',       'FedEx',       TRUE, 'LIVE', 'FEDEX'),
        ('UPS',         'UPS',         TRUE, 'LIVE', 'UPS'),
        ('USPS',        'USPS',        TRUE, 'LIVE', 'USPS'),
        ('DHL',         'DHL',         TRUE, 'LIVE', 'DHL'),
        ('STAMPS_COM',  'Stamps.com',  TRUE, 'LIVE', 'USPS'),
        ('USPS_DIRECT', 'USPS Direct', TRUE, 'LIVE', 'USPS')
    ON CONFLICT (carrier_code) DO UPDATE
        SET display_name = EXCLUDED.display_name,
            family       = EXCLUDED.family,
            updated_at   = NOW();
END $$;
