-- Sprint 52 — widen order_label_tracking columns that overflow the JPA
-- default varchar(255):
--
--   error_message   Carrier error responses (FedEx returns nested JSON;
--                   validator dumps can be 500-2000 chars). Live session
--                   log has 10 "value too long for type character
--                   varying(255)" failures on the manual-shipment ERROR
--                   persistence path — the failure-tracking row itself
--                   fails to persist, so the operator gets an even less
--                   actionable error.
--
--   label_file_path Either a signed carrier URL (500-2000 chars on FedEx)
--                   or the base64-encoded label bytes (10 KB – 200 KB).
--                   Sprint 52 PR B (#518) established this column is
--                   used for BOTH content types by different carriers.
--
--   tracking_url    Carrier tracking deep-links often exceed 255 chars
--                   with query params + tokens; not the primary culprit
--                   today but same class of unbounded text.
--
-- All three switch to TEXT (Postgres — no length limit, negligible
-- overhead for short values). Fresh-DB pattern per docs/flyway-fresh-db-
-- guard-pattern.md: to_regclass gate + ALTER COLUMN TYPE (Postgres can
-- widen VARCHAR to TEXT without a full-table rewrite when there's no
-- CHECK constraint).
DO $$
BEGIN
    IF to_regclass('public.order_label_tracking') IS NULL THEN
        RAISE NOTICE 'V31 skipped — order_label_tracking missing (fresh DB before first sync)';
        RETURN;
    END IF;

    ALTER TABLE order_label_tracking
        ALTER COLUMN error_message TYPE TEXT,
        ALTER COLUMN label_file_path TYPE TEXT,
        ALTER COLUMN tracking_url TYPE TEXT;

    RAISE NOTICE 'V31 widened order_label_tracking error_message + label_file_path + tracking_url to TEXT';
END $$;
