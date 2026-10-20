-- BUG-001 I-001 (sub-issues 2 + 3) — teach the canonicalizer about
-- USPS service codes so canonicalCarrierFor('GROUND_ADVANTAGE') →
-- 'USPS' instead of returning 'GROUND_ADVANTAGE' verbatim.
--
-- QA retest (2026-10-20) saw:
--   /orders/934392/carrier-payload-preview → 422
--       "Unsupported carrier: GROUND_ADVANTAGE"
--   /orders/934392/label                   → missing 'service' block
-- Both call canonicalCarrierFor(order.getShipviaCd()); orders carry
-- the raw service code ('GROUND_ADVANTAGE', 'PRIORITY') in
-- ship_via_cd, and the function's hardcoded pattern list only knew
-- 'PRIORITY_MAIL' and 'FIRST_CLASS' (not 'PRIORITY' bare, not
-- 'GROUND_ADVANTAGE', not any of the four other USPS catalog
-- entries).
--
-- Alias-table route (data not code — per the comment on V106 at
-- :234, adding a new carrier/service = one INSERT, no code change).
-- Every row in shipping_service with carrier='USPS' gets an alias
-- entry so the service code → USPS mapping stays in lockstep with
-- the catalog. Future USPS products are a single INSERT.
--
-- Fresh-DB guard per the project convention (docs/flyway-fresh-db-
-- guard-pattern.md): skip if the table isn't there yet; ON CONFLICT
-- so a reseed of the base V106 rows doesn't clash.

DO $$
BEGIN
    IF to_regclass('public.carrier_alias') IS NULL THEN
        RETURN;
    END IF;
    INSERT INTO carrier_alias (source_code, target_code, display_label) VALUES
        ('GROUND_ADVANTAGE',  'USPS', 'USPS'),
        ('PRIORITY',          'USPS', 'USPS'),
        ('PRIORITY_EXPRESS',  'USPS', 'USPS'),
        ('PRIORITY_INTL',     'USPS', 'USPS'),
        ('PRIORITY_MAIL',     'USPS', 'USPS'),
        ('PRIORITY_MAIL_EXPRESS', 'USPS', 'USPS'),
        ('FIRST_CLASS',       'USPS', 'USPS'),
        ('FIRST_CLASS_INTL',  'USPS', 'USPS'),
        ('EXPRESS_INTL',      'USPS', 'USPS'),
        ('PARCEL_SELECT',     'USPS', 'USPS'),
        ('MEDIA_MAIL',        'USPS', 'USPS')
    ON CONFLICT (source_code) DO NOTHING;
END $$;
