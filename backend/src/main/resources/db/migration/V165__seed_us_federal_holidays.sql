-- BUG-156 R7: the holiday seed shipped only Jan 1 + Dec 24 + Dec 25 for
-- the current and next year, so a shipment created after cutoff on 25
-- November rolled onto Thanksgiving — a day UPS / FedEx / USPS do not
-- run standard service. The '(next year)' suffix on the next-year names
-- also reads wrong after 1 January. Both complaints fixed here.
--
-- Non-working US federal / carrier holidays (common standard-service
-- closures for UPS / FedEx / USPS): Memorial Day (last Mon of May),
-- Independence Day, Labor Day (1st Mon of Sep), Thanksgiving (4th Thu
-- of Nov), Christmas Day, New Year's Day. Christmas Eve stays in the
-- list (shortened pickup day many carriers skip in practice) but no
-- longer carries the 'Christmas Eve' name's '(next year)' suffix for
-- rows already seeded under V97.
--
-- Idempotent: ON CONFLICT (holiday_date) DO NOTHING; the name update
-- strips the stale '(next year)' suffix only when it's actually there.
DO $$
DECLARE
    y   INTEGER;
    y_next INTEGER;
BEGIN
    IF to_regclass('public.holiday') IS NULL THEN
        RAISE NOTICE 'V165 skipped — holiday table missing';
        RETURN;
    END IF;

    y := EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER;
    y_next := y + 1;

    -- Common US carrier-closure dates for the current + next year.
    -- Floating holidays computed inline (Memorial = last Mon of May,
    -- Labor = 1st Mon of Sep, Thanksgiving = 4th Thu of Nov).
    FOR year IN y..y_next LOOP
        INSERT INTO public.holiday (holiday_date, name) VALUES
            (make_date(year, 1, 1),   'New Year''s Day'),
            (make_date(year, 7, 4),   'Independence Day'),
            (make_date(year, 12, 25), 'Christmas Day')
        ON CONFLICT (holiday_date) DO NOTHING;

        -- Memorial Day: last Monday of May.
        INSERT INTO public.holiday (holiday_date, name)
        SELECT d::date, 'Memorial Day'
          FROM generate_series(
                  make_date(year, 5, 25),
                  make_date(year, 5, 31),
                  '1 day'::interval) AS d
         WHERE EXTRACT(DOW FROM d) = 1
         ORDER BY d DESC LIMIT 1
        ON CONFLICT (holiday_date) DO NOTHING;

        -- Labor Day: first Monday of September.
        INSERT INTO public.holiday (holiday_date, name)
        SELECT d::date, 'Labor Day'
          FROM generate_series(
                  make_date(year, 9, 1),
                  make_date(year, 9, 7),
                  '1 day'::interval) AS d
         WHERE EXTRACT(DOW FROM d) = 1
         ORDER BY d LIMIT 1
        ON CONFLICT (holiday_date) DO NOTHING;

        -- Thanksgiving: fourth Thursday of November.
        INSERT INTO public.holiday (holiday_date, name)
        SELECT d::date, 'Thanksgiving Day'
          FROM generate_series(
                  make_date(year, 11, 1),
                  make_date(year, 11, 30),
                  '1 day'::interval) AS d
         WHERE EXTRACT(DOW FROM d) = 4
         ORDER BY d
         LIMIT 1 OFFSET 3
        ON CONFLICT (holiday_date) DO NOTHING;
    END LOOP;

    -- Strip the stale '(next year)' suffix on rows that V97 seeded with
    -- it. Operators looking at the list after 1 Jan should not see a
    -- relative label on a date that's now this year.
    UPDATE public.holiday
       SET name = regexp_replace(name, '\s*\(next year\)\s*$', '')
     WHERE name ~ '\(next year\)\s*$';
END $$;
