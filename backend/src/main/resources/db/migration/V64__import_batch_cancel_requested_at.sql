-- V64 - STAMPS_COM PR-S2: cancel_requested_at column on import_batch.
--
-- Audit finding S-B3 (2026-09-17 stamps-com cross-flow audit):
--
--   Cancelling an in-flight import batch relied on an in-memory
--   {@code Set<Long> cancelledBatchIds} inside OrderImportServiceImpl.
--   That set (a) doesn't survive a JVM restart, so a background job
--   resumed after the crash forgets the operator cancelled it, and
--   (b) isn't visible to other JVMs in a multi-instance deploy — a
--   second instance running the same background job wouldn't see the
--   cancel signal from the first.
--
--   The USPS_DIRECT G3b cancel-cascade for queue rows works because
--   that path has a persistent table to flip. Stamps has no queue; the
--   fix here mirrors the USPS_DIRECT durability but at the batch level.
--
-- Row shape after V64:
--   cancel_requested_at TIMESTAMP NULL - wall-clock the operator's
--                                        cancel arrived. NULL for
--                                        pre-S2 rows and any batch that
--                                        was never cancelled. Workers
--                                        poll this column before every
--                                        row to honour a cross-JVM
--                                        cancel signal.
--
-- Nullable so backfill isn't required; existing rows behave exactly as
-- before (in-memory {@code cancelledBatchIds} still fires the immediate
-- signal; the column adds durability for restarts + cross-instance).
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md - the
-- to_regclass guard skips the ALTER when Hibernate has already created
-- the column from the entity on a truly fresh DB.

DO $$
BEGIN
    IF to_regclass('public.import_batch') IS NOT NULL THEN
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'import_batch'
                   AND column_name = 'cancel_requested_at') THEN
            ALTER TABLE public.import_batch
                ADD COLUMN cancel_requested_at TIMESTAMP;
            COMMENT ON COLUMN public.import_batch.cancel_requested_at IS
                'PR-S2 (S-B3): wall-clock the operator cancelled this batch. Non-null = workers must stop dispatching further rows. Cleared on Retry when the batch flips back to IN_PROGRESS.';
        END IF;
    END IF;
END
$$;
