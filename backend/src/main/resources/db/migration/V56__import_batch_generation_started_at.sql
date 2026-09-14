-- Processing time on Import history.
--
-- completed_at already records when a generate run landed terminal; pair it
-- with the moment the run claimed the batch so the UI can show how long the
-- run took ("took 2m 03s") and tick a live elapsed timer while it is running.
ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS generation_started_at timestamp;
