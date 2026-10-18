-- BUG-102 — the holiday seed shipped Dec 24 (Christmas Eve) + Jan 1 but
-- not Dec 25 (Christmas Day). All four US carriers treat Dec 25 as a
-- non-working day, so shipments scheduled for that day silently rolled
-- forward via each carrier's own cutoff instead of being flagged here.
-- Seed current + next year.
--
-- Idempotent: ON CONFLICT uses the unique holiday_date index.
DO $$
BEGIN
    IF to_regclass('public.holiday') IS NOT NULL THEN
        INSERT INTO holiday (holiday_date, name, created_at, updated_at) VALUES
            (DATE_TRUNC('year', CURRENT_DATE) + INTERVAL '11 months 24 days',
             'Christmas Day', NOW(), NOW()),
            (DATE_TRUNC('year', CURRENT_DATE) + INTERVAL '1 year 11 months 24 days',
             'Christmas Day (next year)', NOW(), NOW())
        ON CONFLICT (holiday_date) DO NOTHING;
    END IF;
END $$;
