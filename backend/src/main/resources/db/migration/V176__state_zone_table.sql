-- G2 — country-aware state → IANA zone map, driving timestamps that
-- need a DST-aware offset (FedEx readyDateTimestamp, future UPS /
-- DHL EOD / pickup slots). Replaces the hardcoded map that was
-- about to land in FedExConnector; follows the carrier_alias (V106)
-- doctrine — "adding a new mapping tomorrow = one INSERT, no code
-- change."
--
-- Lookup is hot (every FedEx pickup) so callers consult
-- StateZoneHolder, a static cache loaded on ApplicationReadyEvent.
-- Row updates via the admin controller invalidate the cache.
--
-- Split-zone states (FL, IN, KY, MI, TN, TX, OR, ID, NE, ND, SD,
-- KS) map to the DOMINANT zone where the population centre lives.
-- A shipper in the minority sliver drifts by at most 1h — well
-- inside carrier pickup windows. Per-ZIP overrides can land as a
-- follow-up when ops reports a real-world miss.
--
-- DST handling: zone_id is an IANA zone (America/New_York etc.),
-- not a fixed offset. Caller resolves the offset for the specific
-- date at consumption time — a Michigan pickup on 2026-10-15 (EDT)
-- and 2026-11-15 (EST) get the correct offsets automatically.
--
-- Fresh-DB guard per docs/flyway-fresh-db-guard-pattern.md.

CREATE TABLE IF NOT EXISTS state_zone (
    country_code VARCHAR(2)  NOT NULL,
    state_code   VARCHAR(10) NOT NULL,
    zone_id      VARCHAR(60) NOT NULL,
    display_name VARCHAR(80),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(120),
    PRIMARY KEY (country_code, state_code)
);

-- Seed: 50 US states + DC + 5 territories. Dominant zones for split
-- states; AZ (no DST) → America/Phoenix; HI → Pacific/Honolulu; PR
-- (AST year-round) → America/Puerto_Rico.
--
-- ON CONFLICT DO NOTHING so a reseed on an existing deployment
-- (ops already added a row) doesn't clobber local tweaks.
INSERT INTO state_zone (country_code, state_code, zone_id, display_name) VALUES
    -- Eastern
    ('US', 'CT', 'America/New_York', 'Connecticut'),
    ('US', 'DE', 'America/New_York', 'Delaware'),
    ('US', 'DC', 'America/New_York', 'District of Columbia'),
    ('US', 'FL', 'America/New_York', 'Florida'),
    ('US', 'GA', 'America/New_York', 'Georgia'),
    ('US', 'IN', 'America/New_York', 'Indiana'),
    ('US', 'KY', 'America/New_York', 'Kentucky'),
    ('US', 'ME', 'America/New_York', 'Maine'),
    ('US', 'MD', 'America/New_York', 'Maryland'),
    ('US', 'MA', 'America/New_York', 'Massachusetts'),
    ('US', 'MI', 'America/New_York', 'Michigan'),
    ('US', 'NH', 'America/New_York', 'New Hampshire'),
    ('US', 'NJ', 'America/New_York', 'New Jersey'),
    ('US', 'NY', 'America/New_York', 'New York'),
    ('US', 'NC', 'America/New_York', 'North Carolina'),
    ('US', 'OH', 'America/New_York', 'Ohio'),
    ('US', 'PA', 'America/New_York', 'Pennsylvania'),
    ('US', 'RI', 'America/New_York', 'Rhode Island'),
    ('US', 'SC', 'America/New_York', 'South Carolina'),
    ('US', 'VT', 'America/New_York', 'Vermont'),
    ('US', 'VA', 'America/New_York', 'Virginia'),
    ('US', 'WV', 'America/New_York', 'West Virginia'),
    -- Central
    ('US', 'AL', 'America/Chicago', 'Alabama'),
    ('US', 'AR', 'America/Chicago', 'Arkansas'),
    ('US', 'IL', 'America/Chicago', 'Illinois'),
    ('US', 'IA', 'America/Chicago', 'Iowa'),
    ('US', 'KS', 'America/Chicago', 'Kansas'),
    ('US', 'LA', 'America/Chicago', 'Louisiana'),
    ('US', 'MN', 'America/Chicago', 'Minnesota'),
    ('US', 'MS', 'America/Chicago', 'Mississippi'),
    ('US', 'MO', 'America/Chicago', 'Missouri'),
    ('US', 'NE', 'America/Chicago', 'Nebraska'),
    ('US', 'ND', 'America/Chicago', 'North Dakota'),
    ('US', 'OK', 'America/Chicago', 'Oklahoma'),
    ('US', 'SD', 'America/Chicago', 'South Dakota'),
    ('US', 'TN', 'America/Chicago', 'Tennessee'),
    ('US', 'TX', 'America/Chicago', 'Texas'),
    ('US', 'WI', 'America/Chicago', 'Wisconsin'),
    -- Mountain (DST)
    ('US', 'CO', 'America/Denver', 'Colorado'),
    ('US', 'ID', 'America/Denver', 'Idaho'),
    ('US', 'MT', 'America/Denver', 'Montana'),
    ('US', 'NM', 'America/Denver', 'New Mexico'),
    ('US', 'UT', 'America/Denver', 'Utah'),
    ('US', 'WY', 'America/Denver', 'Wyoming'),
    -- Mountain no-DST
    ('US', 'AZ', 'America/Phoenix', 'Arizona'),
    -- Pacific
    ('US', 'CA', 'America/Los_Angeles', 'California'),
    ('US', 'NV', 'America/Los_Angeles', 'Nevada'),
    ('US', 'OR', 'America/Los_Angeles', 'Oregon'),
    ('US', 'WA', 'America/Los_Angeles', 'Washington'),
    -- Alaska / Hawaii
    ('US', 'AK', 'America/Anchorage', 'Alaska'),
    ('US', 'HI', 'Pacific/Honolulu', 'Hawaii'),
    -- Territories (AST / ChST / SST — no DST)
    ('US', 'PR', 'America/Puerto_Rico', 'Puerto Rico'),
    ('US', 'VI', 'America/St_Thomas', 'U.S. Virgin Islands'),
    ('US', 'GU', 'Pacific/Guam', 'Guam'),
    ('US', 'MP', 'Pacific/Guam', 'Northern Mariana Islands'),
    ('US', 'AS', 'Pacific/Pago_Pago', 'American Samoa')
ON CONFLICT (country_code, state_code) DO NOTHING;
