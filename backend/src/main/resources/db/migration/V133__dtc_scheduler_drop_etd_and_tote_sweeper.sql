-- V133 — drop the FedEx end-of-day (ETD upload) and errored-tote sweeper jobs
-- seeded by V132. Neither was implemented and they are out of scope; the DTC
-- scheduler now only runs DTC_SYNC. Their windows go with them (ON DELETE
-- CASCADE); their run history rows are removed too.

DELETE FROM public.dtc_scheduler_run
 WHERE job_key IN ('FEDEX_ETD', 'ERRORED_TOTE_SWEEPER');

DELETE FROM public.dtc_scheduler_job
 WHERE job_key IN ('FEDEX_ETD', 'ERRORED_TOTE_SWEEPER');
