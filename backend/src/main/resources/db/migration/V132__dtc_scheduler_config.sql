-- V132 — DB-driven DTC background scheduler (Settings → Integrations → DTC Scheduler).
--
-- Replaces the hard-coded (and commented-out) @Scheduled crons in
-- DtcTimedBackgroundService. Admins edit the timings on the page; the engine
-- (DtcSchedulerEngine) re-reads these tables every minute, so a change takes
-- effect on the next tick with no restart.
--
--   dtc_scheduler_settings  one row: master switch + timezone
--   dtc_scheduler_job       one row per job (sync, FedEx ETD, tote sweeper)
--   dtc_scheduler_window    when a job runs: days × time range × every N min,
--                           or once a day at run_at
--   dtc_scheduler_run       run history shown on the page
--
-- Seeded with the ShipXSync shipped defaults. The master switch starts OFF so
-- a deploy never starts pulling from Oracle by itself — an admin turns it on.

CREATE TABLE IF NOT EXISTS public.dtc_scheduler_settings (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    -- IANA tz. Null = the server's local time zone.
    timezone    VARCHAR(64),
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS public.dtc_scheduler_job (
    id               BIGSERIAL PRIMARY KEY,
    job_key          VARCHAR(40)  NOT NULL UNIQUE,
    name             VARCHAR(100) NOT NULL,
    description      VARCHAR(500),
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    -- Job-specific settings as JSON text, e.g. {"minAgeMinutes":15,"batchSize":100}.
    params_json      TEXT,
    sort_order       INT NOT NULL DEFAULT 0,
    -- Cluster lease: a node running the job holds it until lease_until, so a
    -- second node (or the next tick on the same node) skips instead of overlapping.
    lease_until      TIMESTAMP,
    lease_owner      VARCHAR(100),
    last_run_at      TIMESTAMP,
    last_status      VARCHAR(20),
    last_message     VARCHAR(1000),
    last_duration_ms BIGINT,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by       VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS public.dtc_scheduler_window (
    id                BIGSERIAL PRIMARY KEY,
    job_id            BIGINT NOT NULL REFERENCES public.dtc_scheduler_job(id) ON DELETE CASCADE,
    label             VARCHAR(100),
    -- Comma list of MON..SUN — the day the run happens on.
    days              VARCHAR(40) NOT NULL,
    -- Interval window: start_time..end_time inclusive, may cross midnight
    -- (22:00..05:59). Runs at start_time, then every interval_minutes.
    start_time        TIME,
    end_time          TIME,
    interval_minutes  INT CHECK (interval_minutes BETWEEN 1 AND 1440),
    -- Daily window: runs once at run_at. Exactly one of interval / run_at is set.
    run_at            TIME,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order        INT NOT NULL DEFAULT 0,
    CONSTRAINT ck_dtc_scheduler_window_shape CHECK (
        (run_at IS NOT NULL AND interval_minutes IS NULL)
        OR (run_at IS NULL AND interval_minutes IS NOT NULL
            AND start_time IS NOT NULL AND end_time IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS ix_dtc_scheduler_window_job ON public.dtc_scheduler_window (job_id);

CREATE TABLE IF NOT EXISTS public.dtc_scheduler_run (
    id            BIGSERIAL PRIMARY KEY,
    job_key       VARCHAR(40) NOT NULL,
    -- SCHEDULE | MANUAL
    trigger_type  VARCHAR(20) NOT NULL,
    triggered_by  VARCHAR(200),
    started_at    TIMESTAMP NOT NULL,
    finished_at   TIMESTAMP,
    -- RUNNING | SUCCESS | FAILED | SKIPPED
    status        VARCHAR(20) NOT NULL,
    message       VARCHAR(1000),
    duration_ms   BIGINT
);
CREATE INDEX IF NOT EXISTS ix_dtc_scheduler_run_job_started ON public.dtc_scheduler_run (job_key, started_at DESC);

-- ── Seed: ShipXSync shipped defaults ────────────────────────────────────────

INSERT INTO public.dtc_scheduler_settings (id, enabled, timezone, updated_by)
VALUES (1, FALSE, NULL, 'V132')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.dtc_scheduler_job (job_key, name, description, enabled, params_json, sort_order, updated_by)
VALUES
    ('DTC_SYNC', 'DTC sync and labels',
     'Pulls pending DTC orders from Oracle WMS for every tenant.', TRUE, NULL, 10, 'V132'),
    ('FEDEX_ETD', 'FedEx end-of-day (ETD upload)',
     'Uploads the day''s commercial invoices to FedEx ETD. Not implemented yet.', FALSE, NULL, 20, 'V132'),
    ('ERRORED_TOTE_SWEEPER', 'Errored-tote sweeper',
     'Retries errored totes older than the minimum age. Not implemented yet.', FALSE,
     '{"minAgeMinutes":15,"batchSize":100}', 30, 'V132')
ON CONFLICT (job_key) DO NOTHING;

INSERT INTO public.dtc_scheduler_window (job_id, label, days, start_time, end_time, interval_minutes, run_at, sort_order)
SELECT j.id, w.label, w.days, w.start_time, w.end_time, w.interval_minutes, w.run_at, w.sort_order
  FROM public.dtc_scheduler_job j
  JOIN (VALUES
        ('DTC_SYNC', 'Weekday morning', 'MON,TUE,WED,THU,FRI', TIME '06:00', TIME '11:59', 5,    NULL::TIME,  10),
        ('DTC_SYNC', 'Weekday peak',    'MON,TUE,WED,THU,FRI', TIME '12:00', TIME '21:59', 1,    NULL::TIME,  20),
        ('DTC_SYNC', 'Weekday night',   'MON,TUE,WED,THU,FRI', TIME '22:00', TIME '05:59', 30,   NULL::TIME,  30),
        ('DTC_SYNC', 'Weekend',         'SAT,SUN',             TIME '00:00', TIME '23:59', 5,    NULL::TIME,  40),
        ('FEDEX_ETD', 'Daily',          'MON,TUE,WED,THU,FRI,SAT,SUN', NULL, NULL,         NULL, TIME '21:00', 10),
        ('ERRORED_TOTE_SWEEPER', 'All day', 'MON,TUE,WED,THU,FRI,SAT,SUN', TIME '00:00', TIME '23:59', 15, NULL::TIME, 10)
       ) AS w(job_key, label, days, start_time, end_time, interval_minutes, run_at, sort_order)
    ON w.job_key = j.job_key
 WHERE NOT EXISTS (SELECT 1 FROM public.dtc_scheduler_window x WHERE x.job_id = j.id);
