-- V63 - USPS_DIRECT PR-G3b: source_type + import_batch_id columns on usps_label_queue.
--
-- Two related gaps left after PR-G3a (2026-09-15 audit findings U6 + M-B4):
--
--   U6 — The admin dashboard aggregates queue depth WITHOUT a source
--        dimension, so operators can't answer "is this spike from a
--        background import job or the manual label modal?". Every fix
--        for a runaway import needs that answer first.
--
--   M-B4 — Cancelling an in-flight import batch didn't cascade to the
--        queue. The batch flipped CANCELLED but the queue kept draining
--        rows the operator asked to stop. Fixed by (a) recording which
--        import_batch each queue row belongs to at enqueue time, then
--        (b) OrderImportService.cancelGeneration invoking
--        uspsLabelQueueService.cancelPending(importBatchId).
--
-- Row shape after V63:
--   source_type      VARCHAR(24) NULL - origin of the enqueue. Values:
--                                       BULK_OPERATOR | IMPORT_OPERATOR |
--                                       IMPORT_BACKGROUND | MPS_PIECE |
--                                       MANUAL. NULL for pre-G3b rows.
--   import_batch_id  BIGINT NULL     - FK-analog to import_batch.id when
--                                       the enqueue came via OrderImportServiceImpl
--                                       (operator OR background). NULL for
--                                       BULK_OPERATOR / MANUAL / standalone MPS
--                                       (splitter without an import context).
--
-- Not adding a formal FK to import_batch: the queue outlives the batch
-- (a CANCELLED batch keeps its DONE queue rows for auditing), and a
-- CASCADE DELETE on a queue row we already dispatched to USPS would
-- lose the tracking-number history. Cancel handling lives in application
-- code (uspsLabelQueueService.cancelPending) which only touches QUEUED
-- rows.
--
-- Indexes:
--   idx_usps_label_queue_import_batch — partial, only rows tied to an
--        import batch. Optimises the cancel-cascade lookup + any future
--        "show me all queue rows for import #N" admin drill-down.
--   idx_usps_label_queue_source_type  — partial, source_type NOT NULL.
--        Optimises the dashboard's GROUP BY source_type aggregation.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md - the
-- to_regclass guard skips the ALTER when Hibernate has already created
-- the columns from the entity on a truly fresh DB.

DO $$
BEGIN
    IF to_regclass('public.usps_label_queue') IS NOT NULL THEN
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'usps_label_queue'
                   AND column_name = 'source_type') THEN
            ALTER TABLE public.usps_label_queue
                ADD COLUMN source_type VARCHAR(24);
            COMMENT ON COLUMN public.usps_label_queue.source_type IS
                'PR-G3b: origin of the enqueue — BULK_OPERATOR | IMPORT_OPERATOR | IMPORT_BACKGROUND | MPS_PIECE | MANUAL. Enables admin dashboard filter for "which surface caused the spike?".';
        END IF;

        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'usps_label_queue'
                   AND column_name = 'import_batch_id') THEN
            ALTER TABLE public.usps_label_queue
                ADD COLUMN import_batch_id BIGINT;
            COMMENT ON COLUMN public.usps_label_queue.import_batch_id IS
                'PR-G3b: FK-analog to import_batch.id when the enqueue came via OrderImportServiceImpl (operator or background). NULL for BULK_OPERATOR / MANUAL / standalone MPS. Enables cancel-cascade on import batch cancellation.';
        END IF;

        -- Partial index: only rows that belong to an import batch pay
        -- the index cost. The cancel-cascade + admin drill-down are the
        -- only readers and both filter by import_batch_id IS NOT NULL.
        CREATE INDEX IF NOT EXISTS idx_usps_label_queue_import_batch
            ON public.usps_label_queue (import_batch_id, status)
            WHERE import_batch_id IS NOT NULL;

        -- Partial index: only rows with a stamped source_type pay the
        -- index cost. Pre-G3b rows (source_type NULL) skip the index so
        -- the write path stays cheap during the backfill window.
        CREATE INDEX IF NOT EXISTS idx_usps_label_queue_source_type
            ON public.usps_label_queue (source_type, status)
            WHERE source_type IS NOT NULL;
    END IF;
END $$;
