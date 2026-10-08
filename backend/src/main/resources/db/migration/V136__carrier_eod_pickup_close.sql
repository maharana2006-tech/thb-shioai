-- V135 — Carrier pickups & end-of-day close-out: an audit log for every
-- pickup/close event (manual from Settings → Pickups & End-of-Day, or from the
-- scheduled CARRIER_CLOSEOUT job), plus the scheduler job that runs the close
-- automatically at end of day.
--
-- The pickup/close carrier calls themselves already existed (PickupService /
-- ManifestService + connectors). This migration adds (a) persistence so a
-- scheduled close that fails leaves a trace, and (b) the scheduler job row so
-- the existing DTC Scheduler engine runs an automatic end-of-day close.

CREATE TABLE IF NOT EXISTS public.carrier_eod_log (
    id             BIGSERIAL PRIMARY KEY,
    -- PICKUP | CLOSEOUT
    kind           VARCHAR(10)  NOT NULL,
    carrier_code   VARCHAR(20)  NOT NULL,
    account_number VARCHAR(100),
    customer_no    VARCHAR(100),
    warehouse_code VARCHAR(50),
    -- Pickup date or close date.
    event_date     DATE,
    -- Carrier confirmation number (pickup) or manifest id / BOL (close).
    reference      VARCHAR(200),
    tracking_count INT NOT NULL DEFAULT 0,
    -- Mirrors the response status: SCHEDULED/MANIFESTED/PARTIAL/ERROR/NOT_SUPPORTED/EMPTY.
    status         VARCHAR(20)  NOT NULL,
    message        VARCHAR(1000),
    -- MANUAL | SCHEDULED
    source         VARCHAR(20)  NOT NULL,
    created_by     VARCHAR(200),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_carrier_eod_log_created ON public.carrier_eod_log (created_at DESC);

-- ── Scheduler job: automatic end-of-day close ───────────────────────────────
-- Runs once at end of day on weekdays; closes out that day's open labels for
-- each configured carrier. Starts OFF — an admin turns it on from the DTC
-- Scheduler page. params_json:
--   carriers       which carriers to close (default FEDEX, UPS, USPS)
--   customerNo     "" = every client's labels, else only this client/tenant
--   warehouseCode  "" = every warehouse, else only this warehouse

INSERT INTO public.dtc_scheduler_job (job_key, name, description, enabled, params_json, sort_order, updated_by)
VALUES ('CARRIER_CLOSEOUT', 'Carrier end-of-day close',
        'Closes out the day''s open labels at each carrier (manifest / SCAN form) automatically.', FALSE,
        '{"carriers":["FEDEX","UPS","USPS"],"customerNo":"","warehouseCode":""}', 40, 'V135')
ON CONFLICT (job_key) DO NOTHING;

INSERT INTO public.dtc_scheduler_window (job_id, label, days, start_time, end_time, interval_minutes, run_at, sort_order)
SELECT j.id, 'End of day', 'MON,TUE,WED,THU,FRI', NULL, NULL, NULL, TIME '20:00', 10
  FROM public.dtc_scheduler_job j
 WHERE j.job_key = 'CARRIER_CLOSEOUT'
   AND NOT EXISTS (SELECT 1 FROM public.dtc_scheduler_window x WHERE x.job_id = j.id);
