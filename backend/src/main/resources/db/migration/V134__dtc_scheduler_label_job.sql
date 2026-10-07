-- V134 — DTC label generation as its own scheduler job (DTC_LABELS), with its
-- own windows and settings, separate from DTC_SYNC.
--
-- Each run finds (tenant, batch) pairs with lines never attempted
-- (generated_status empty) that were synced within lookbackHours, and queues
-- them through DtcLabelGenerationService.enqueue — the same path as the D2C
-- Generate button. Starts OFF: an admin turns it on from the page.
--
-- params_json:
--   tenants           [] = every tenant, else only these
--   lookbackHours     only lines synced in the last N hours (so turning the
--                     job on never labels historical batches)
--   maxBatchesPerRun  cap on batches queued per run

INSERT INTO public.dtc_scheduler_job (job_key, name, description, enabled, params_json, sort_order, updated_by)
VALUES ('DTC_LABELS', 'DTC label generation',
        'Queues label generation for newly synced DTC batches that have no labels yet.', FALSE,
        '{"tenants":[],"lookbackHours":24,"maxBatchesPerRun":20}', 20, 'V134')
ON CONFLICT (job_key) DO NOTHING;

INSERT INTO public.dtc_scheduler_window (job_id, label, days, start_time, end_time, interval_minutes, run_at, sort_order)
SELECT j.id, w.label, w.days, w.start_time, w.end_time, w.interval_minutes, NULL, w.sort_order
  FROM public.dtc_scheduler_job j
  JOIN (VALUES
        ('Weekday morning', 'MON,TUE,WED,THU,FRI', TIME '06:00', TIME '11:59', 5,  10),
        ('Weekday peak',    'MON,TUE,WED,THU,FRI', TIME '12:00', TIME '21:59', 1,  20),
        ('Weekday night',   'MON,TUE,WED,THU,FRI', TIME '22:00', TIME '05:59', 30, 30),
        ('Weekend',         'SAT,SUN',             TIME '00:00', TIME '23:59', 5,  40)
       ) AS w(label, days, start_time, end_time, interval_minutes, sort_order)
    ON TRUE
 WHERE j.job_key = 'DTC_LABELS'
   AND NOT EXISTS (SELECT 1 FROM public.dtc_scheduler_window x WHERE x.job_id = j.id);
