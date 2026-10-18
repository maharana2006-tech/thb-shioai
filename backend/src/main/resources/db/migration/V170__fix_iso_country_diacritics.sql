-- BUG-060 follow-up — V151 seeded the ISO 3166-1 country list from an ASCII
-- source, so the seven names that carry diacritics lost them (e.g. "land
-- Islands", "Cte d'Ivoire", "Curaao", "Trkiye"). The Countries settings page
-- renders the stored name verbatim, so the mojibake shows through in the UI.
--
-- Restore the correct UTF-8 spellings. Guarded on the still-corrupted value so
-- an operator who already fixed a name by hand is not overwritten (V151 kept
-- the same "preserve operator edits" promise). Idempotent — re-running is a
-- no-op once the names are correct.
DO $$
BEGIN
    IF to_regclass('public.country') IS NOT NULL THEN
        UPDATE country SET name = 'Åland Islands', updated_at = NOW()
            WHERE country_code = 'AX' AND name = 'land Islands';
        UPDATE country SET name = 'Saint Barthélemy', updated_at = NOW()
            WHERE country_code = 'BL' AND name = 'Saint Barthlemy';
        UPDATE country SET name = 'Côte d''Ivoire', updated_at = NOW()
            WHERE country_code = 'CI' AND name = 'Cte d''Ivoire';
        UPDATE country SET name = 'Curaçao', updated_at = NOW()
            WHERE country_code = 'CW' AND name = 'Curaao';
        UPDATE country SET name = 'Réunion', updated_at = NOW()
            WHERE country_code = 'RE' AND name = 'Runion';
        UPDATE country SET name = 'São Tomé and Príncipe', updated_at = NOW()
            WHERE country_code = 'ST' AND name = 'Sao Tome and Principe';
        UPDATE country SET name = 'Türkiye', updated_at = NOW()
            WHERE country_code = 'TR' AND name = 'Trkiye';
    END IF;
END $$;
