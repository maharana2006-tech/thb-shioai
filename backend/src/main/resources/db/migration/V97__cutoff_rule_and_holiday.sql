-- V97 — cutoff rule matrix + global holiday list (G7).
--
-- Per ShipX_NDS_Orders_and_Tracking.docx §10: shipments generated after
-- the daily cutoff or on a holiday get their SHIP_DATE pushed to the
-- next working day (Sat/Sun/holiday-skipping). Operator direction on
-- 2026-09-28: configure via a (source × carrier × warehouse) rule table
-- so an admin can tune which combinations get the shift. Cutoff time
-- + timezone live on each rule row. Holidays are a global list.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema='public' AND table_name='cutoff_rule') THEN
        CREATE TABLE public.cutoff_rule (
            id             BIGSERIAL PRIMARY KEY,
            -- Nullable = "any". A rule with all three null applies to every
            -- shipment; a rule with (source='DTC', carrier=null, warehouse=null)
            -- applies to every DTC shipment on any carrier / any warehouse.
            source         VARCHAR(20),
            carrier_code   VARCHAR(20),
            warehouse_id   BIGINT REFERENCES public.warehouse(id) ON DELETE CASCADE,
            -- Local time on the rule's timezone. Default 20:00 per doc.
            cutoff_time    TIME NOT NULL DEFAULT '20:00:00',
            -- IANA tz. Null = client's tz per Client.timezone.
            timezone       VARCHAR(64),
            active         BOOLEAN NOT NULL DEFAULT TRUE,
            created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_by     VARCHAR(200)
        );
        COMMENT ON TABLE public.cutoff_rule IS
            'G7 — per (source, carrier, warehouse) cutoff rule. When any active rule matches, past-cutoff shipments get SHIP_DATE pushed to next working day.';
        CREATE INDEX ix_cutoff_rule_lookup ON public.cutoff_rule (active, warehouse_id, carrier_code, source);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema='public' AND table_name='holiday') THEN
        CREATE TABLE public.holiday (
            id             BIGSERIAL PRIMARY KEY,
            holiday_date   DATE NOT NULL,
            name           VARCHAR(100) NOT NULL,
            active         BOOLEAN NOT NULL DEFAULT TRUE,
            created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_by     VARCHAR(200),
            CONSTRAINT uk_holiday_date UNIQUE (holiday_date)
        );
        COMMENT ON TABLE public.holiday IS
            'G7 — global holiday list. cutoff logic skips these dates when computing next working day.';
        -- Seed doc-mandated defaults (Dec 24 + Jan 1 for the current year + next).
        INSERT INTO public.holiday (holiday_date, name)
        VALUES
            (make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int,     12, 24), 'Christmas Eve'),
            (make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int + 1, 12, 24), 'Christmas Eve (next year)'),
            (make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int,      1,  1), 'New Year''s Day'),
            (make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int + 1,  1,  1), 'New Year''s Day (next year)')
        ON CONFLICT (holiday_date) DO NOTHING;
    END IF;
END
$$;
