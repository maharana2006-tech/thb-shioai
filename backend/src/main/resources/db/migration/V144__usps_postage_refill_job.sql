-- V140 — scheduled USPS postage auto-refill (USPS_POSTAGE_REFILL), on the DTC
-- scheduler engine. Each run tops up every authorized USPS (Stamps.com) account
-- whose balance is below thresholdUsd, by refillUsd, via the idempotent
-- add-funds path. Starts OFF — an admin turns it on from the DTC Scheduler page;
-- thresholdUsd / refillUsd are editable there (numeric params).

INSERT INTO public.dtc_scheduler_job (job_key, name, description, enabled, params_json, sort_order, updated_by)
VALUES ('USPS_POSTAGE_REFILL', 'USPS postage auto-refill',
        'Tops up each authorized USPS (Stamps.com) account when its balance drops below the threshold.', FALSE,
        '{"thresholdUsd":25,"refillUsd":50}', 50, 'V140')
ON CONFLICT (job_key) DO NOTHING;

INSERT INTO public.dtc_scheduler_window (job_id, label, days, start_time, end_time, interval_minutes, run_at, sort_order)
SELECT j.id, 'Business hours', 'MON,TUE,WED,THU,FRI', TIME '08:00', TIME '18:00', 60, NULL, 10
  FROM public.dtc_scheduler_job j
 WHERE j.job_key = 'USPS_POSTAGE_REFILL'
   AND NOT EXISTS (SELECT 1 FROM public.dtc_scheduler_window x WHERE x.job_id = j.id);
