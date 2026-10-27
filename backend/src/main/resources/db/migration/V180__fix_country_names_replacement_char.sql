-- BUG-106 — V151 seeded six country names with the accented letter replaced by
-- U+FFFD; V170 guarded on the ASCII-stripped spelling ('land Islands') so it
-- never matched the rows that actually hold the replacement character. Restore
-- the names by ISO code, whatever the current (wrong) spelling is.
DO $$
BEGIN
    IF to_regclass('public.country') IS NOT NULL THEN
        UPDATE country SET name = 'Åland Islands',    updated_at = NOW() WHERE country_code = 'AX' AND name <> 'Åland Islands';
        UPDATE country SET name = 'Saint Barthélemy', updated_at = NOW() WHERE country_code = 'BL' AND name <> 'Saint Barthélemy';
        UPDATE country SET name = 'Côte d''Ivoire',   updated_at = NOW() WHERE country_code = 'CI' AND name <> 'Côte d''Ivoire';
        UPDATE country SET name = 'Curaçao',          updated_at = NOW() WHERE country_code = 'CW' AND name <> 'Curaçao';
        UPDATE country SET name = 'Réunion',          updated_at = NOW() WHERE country_code = 'RE' AND name <> 'Réunion';
        UPDATE country SET name = 'Türkiye',          updated_at = NOW() WHERE country_code = 'TR' AND name <> 'Türkiye';
    END IF;
END $$;
