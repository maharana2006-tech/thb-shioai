-- Royal Mail (Click & Drop) as a fifth carrier.
--
-- Two data rows are the whole registration — the connector itself is a
-- @Component that CarrierServiceImpl discovers by carrier code:
--   carriers       — platform enable/disable + family (V112 table)
--   carrier_alias  — canonical row that feeds /carriers/known, which is
--                    what the New shipment and bulk carrier dropdowns render
-- ROYALMAIL is accepted as an import alias because ERP/CSV feeds tend to
-- drop the underscore.
--
-- No shipping_service rows are seeded on purpose: Click & Drop service
-- codes are per-account, and ShippingConfigService.syncFromCarrier only
-- persists services from a verified live call (GET /services).

INSERT INTO carriers (carrier_code, display_name, enabled, mode, family)
VALUES ('ROYAL_MAIL', 'Royal Mail', TRUE, 'LIVE', 'ROYAL_MAIL')
ON CONFLICT (carrier_code) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        family       = EXCLUDED.family,
        updated_at   = NOW();

INSERT INTO carrier_alias (source_code, target_code, display_label) VALUES
    ('ROYAL_MAIL', 'ROYAL_MAIL', 'Royal Mail'),
    ('ROYALMAIL',  'ROYAL_MAIL', 'Royal Mail')
ON CONFLICT (source_code) DO UPDATE
    SET target_code   = EXCLUDED.target_code,
        display_label = EXCLUDED.display_label,
        updated_at    = NOW();
