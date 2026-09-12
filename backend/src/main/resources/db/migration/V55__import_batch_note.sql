-- V55 — batch-level note surfaced under the status pill.
--
-- Motivation (Batch #11 post-mortem, 2026-09-12): when the retry-pass
-- loop exhausts with rows still rate-limited by a carrier, the row-level
-- messages ("UPS is still rate-limiting after 4 automatic retries")
-- were the only signal to the operator. A batch-level note ("UPS was
-- rate-limiting throughout — 5,142 rows still queued. Wait for the
-- quota window to reset.") gives the operator immediate context on
-- Data History without expanding the batch.
--
-- Nullable / short (500 chars). Populated by
-- OrderImportServiceImpl.generateLabelsForBatch when the retry-pass
-- loop exits with deferred rows remaining; cleared on the next
-- successful terminal run (via stampCompletionIfTerminal peer).
-- Fresh-DB safe: guarded with to_regclass + information_schema check.

DO $$
BEGIN
    IF to_regclass('public.import_batch') IS NOT NULL
       AND NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'import_batch'
              AND column_name = 'note'
       )
    THEN
        ALTER TABLE public.import_batch
            ADD COLUMN note VARCHAR(500);
    END IF;
END $$;
