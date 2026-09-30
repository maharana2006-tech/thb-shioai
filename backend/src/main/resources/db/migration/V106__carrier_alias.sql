-- C4 — canonical carrier vocabulary + ERP alias registry.
--
-- Retires three duplicated switch statements:
--   ShippingConfigService.canonicalCarrierFor  (BE canonicaliser)
--   User.getCarrierDisplayName                 (JWT / audit display)
--   ZplLabelService.displayCarrier             (label rendering)
-- and a closed-set FE check:
--   NewShipmentPage.tsx  KNOWN_CARRIERS + CARRIER_LABEL
--
-- One row per code:
--   source_code    = whatever appears in an order's ship_via_cd,
--                    a carrier_account_ref.carrier_code, or a UI dropdown value.
--   target_code    = the canonical carrier the source maps to. For a
--                    "canonical" row, source_code == target_code.
--   display_label  = human-readable name shown in the UI.
--
-- Seed covers today's supported set. Adding a new carrier / ERP alias
-- tomorrow = one INSERT + one restart, no code change.

CREATE TABLE carrier_alias (
    source_code    VARCHAR(40)  PRIMARY KEY,
    target_code    VARCHAR(40)  NOT NULL,
    display_label  VARCHAR(60)  NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by     VARCHAR(120)
);

CREATE INDEX ix_carrier_alias_target ON carrier_alias (target_code);

INSERT INTO carrier_alias (source_code, target_code, display_label) VALUES
    -- Canonical carriers (source == target).
    ('UPS',         'UPS',   'UPS'),
    ('FEDEX',       'FEDEX', 'FedEx'),
    ('USPS',        'USPS',  'USPS'),
    ('DHL',         'DHL',   'DHL'),
    -- USPS aliases used by different providers.
    ('STAMPS',      'USPS',  'USPS'),
    ('STAMPS_COM',  'USPS',  'USPS'),
    ('USPS_DIRECT', 'USPS',  'USPS'),
    -- Legacy ERP carrier codes.
    ('P80',         'UPS',   'UPS'),
    ('F77',         'FEDEX', 'FedEx'),
    ('L01',         'USPS',  'USPS');
