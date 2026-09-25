-- Import history, the batch page and the Orders page's batch filter look
-- orders up by label batch number (label_batch.batch_id), which had no
-- index: every Import history load scanned every order of every partition
-- (fast on a test system with a few orders, ~2 minutes on a client's). The
-- table is partitioned, so this is a plain CREATE INDEX (CONCURRENTLY is not
-- allowed on a partitioned table) — it cascades to each partition, and runs
-- at startup before the app serves requests (see V74).
CREATE INDEX IF NOT EXISTS idx_label_batch_batch_id ON label_batch (batch_id);
