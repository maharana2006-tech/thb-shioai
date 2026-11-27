-- DPD as a sixth carrier (skeleton — connector refuses live calls until the
-- DPD entity is confirmed). Same two-row registration as V184:
--   carriers       — platform enable/disable + family
--   carrier_alias  — canonical row that feeds /carriers/known
-- DPDLOCAL is accepted as an import alias for DPD Local feeds.

INSERT INTO carriers (carrier_code, display_name, enabled, mode, family)
VALUES ('DPD', 'DPD', TRUE, 'LIVE', 'DPD')
ON CONFLICT (carrier_code) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        family       = EXCLUDED.family,
        updated_at   = NOW();

INSERT INTO carrier_alias (source_code, target_code, display_label) VALUES
    ('DPD',      'DPD', 'DPD'),
    ('DPDLOCAL', 'DPD', 'DPD')
ON CONFLICT (source_code) DO UPDATE
    SET target_code   = EXCLUDED.target_code,
        display_label = EXCLUDED.display_label,
        updated_at    = NOW();
