-- One active (QUEUED or RUNNING) Automatic-label job per D2C batch.
--
-- enqueue() checked "is a job already active?" and then inserted, so two quick
-- clicks could both pass the check and queue two jobs; the worker runs two jobs
-- at once, so both labelled every new line and UPS was paid twice per line.
-- The partial unique index makes the second insert fail instead.
--
-- Any duplicates already sitting active (older installs) keep the earliest job
-- and close the later ones, so the index can be built.

UPDATE dtc_generation_job j
   SET status = 'FAILED',
       error_message = 'Closed as a duplicate of job ' || first.id || ' (V128: one active job per batch)',
       finished_at = CURRENT_TIMESTAMP
  FROM (SELECT tenant_id, batch_id, MIN(id) AS id
          FROM dtc_generation_job
         WHERE status IN ('QUEUED', 'RUNNING')
         GROUP BY tenant_id, batch_id
        HAVING COUNT(*) > 1) first
 WHERE j.tenant_id = first.tenant_id
   AND j.batch_id = first.batch_id
   AND j.status IN ('QUEUED', 'RUNNING')
   AND j.id <> first.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_dtc_generation_job_active_batch
    ON dtc_generation_job (tenant_id, batch_id)
    WHERE status IN ('QUEUED', 'RUNNING');
