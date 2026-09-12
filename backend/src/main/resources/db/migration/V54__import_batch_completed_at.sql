-- V54 — record when a bulk-import label-generation run finishes.
--
-- Motivation: operators watching Data History could see a batch flip
-- from IN_PROGRESS to COMPLETE / PARTIAL_COMPLETE / FAILED / CANCELLED,
-- but had no way to tell WHEN that transition happened without cross-
-- referencing the log. Stamping completed_at on every terminal
-- transition (and updating it on retries per operator ask 2026-09-12)
-- lets the FE render "completed 3 min ago" next to the status pill.
--
-- Nullable so pre-migration batches stay honest — no faux backfill.
-- New completions from this point forward get the real timestamp.
--
-- Follows the fresh-DB idempotency pattern: guard with to_regclass so
-- the migration is a no-op if the column already exists (rerunnable
-- against a schema that was created via IF NOT EXISTS elsewhere).

DO $$
BEGIN
    IF to_regclass('public.import_batch') IS NOT NULL
       AND NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'import_batch'
              AND column_name = 'completed_at'
       )
    THEN
        ALTER TABLE public.import_batch
            ADD COLUMN completed_at TIMESTAMP(6) WITHOUT TIME ZONE;
    END IF;
END $$;
