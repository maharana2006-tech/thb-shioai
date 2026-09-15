-- Label generation runs as a durable background job.
--
-- A Generate used to run inside the HTTP request (about 5 minutes for 300
-- orders) with its progress and Cancel flag held in JVM memory: a restart lost
-- the run (the startup housekeeper marked it FAILED) and a second server could
-- neither see nor cancel it. Now the request validates, claims the import and
-- enqueues a row here; workers claim rows with SELECT ... FOR UPDATE SKIP LOCKED.
CREATE TABLE IF NOT EXISTS import_generation_job (
    id                   BIGSERIAL PRIMARY KEY,
    import_batch_id      BIGINT       NOT NULL,
    -- QUEUED -> RUNNING -> DONE | FAILED | CANCELLED
    status               VARCHAR(20)  NOT NULL,
    requested_by         VARCHAR(255),
    -- Tenant scope of the requester at enqueue time (null = platform operator).
    -- The worker has no logged-in user, so it enforces this explicitly.
    requested_scope      VARCHAR(60),
    use_platform_account BOOLEAN      NOT NULL DEFAULT FALSE,
    progress_done        INT          NOT NULL DEFAULT 0,
    progress_total       INT          NOT NULL DEFAULT 0,
    note                 VARCHAR(500),
    cancel_requested     BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts             INT          NOT NULL DEFAULT 0,
    worker_id            VARCHAR(120),
    result_status        VARCHAR(30),
    result_message       VARCHAR(1000),
    error_message        VARCHAR(2000),
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    started_at           TIMESTAMP,
    heartbeat_at         TIMESTAMP,
    finished_at          TIMESTAMP
);

-- The queue scan: oldest QUEUED first.
CREATE INDEX IF NOT EXISTS idx_import_gen_job_queue
    ON import_generation_job (status, id) WHERE status IN ('QUEUED', 'RUNNING');

-- At most one live job per import (the IN_PROGRESS claim already guards this;
-- the index makes a double enqueue impossible rather than unlikely).
CREATE UNIQUE INDEX IF NOT EXISTS uq_import_gen_job_active
    ON import_generation_job (import_batch_id) WHERE status IN ('QUEUED', 'RUNNING');

-- Latest job for an import (progress polling, history).
CREATE INDEX IF NOT EXISTS idx_import_gen_job_batch
    ON import_generation_job (import_batch_id, id DESC);
