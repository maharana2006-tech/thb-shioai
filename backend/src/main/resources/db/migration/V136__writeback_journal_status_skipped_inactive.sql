-- PR-I3 hotfix — add SKIPPED_INACTIVE to writeback_journal.status vocabulary.
--
-- I3 shipped a new terminal status for writeback attempts the dispatcher
-- declined to make because the backing external_system_connection row was
-- active=false at dispatch time. The DB-side CHECK constraint from the
-- original V109 journal table didn't know about the new value, so every
-- inactive-skip insert blew up at the DB with 'ck_writeback_journal_status'
-- violation (surfaced as "writeback: generate dispatch failed for order=…"
-- in logs — misleading because the dispatcher's INTENT was to skip, not
-- to fail).
--
-- Fresh-DB-guard pattern: wrapped in a to_regclass check so cold bootstrap
-- that reaches V136 before external_system_writeback_journal exists
-- silently no-ops (see docs/flyway-fresh-db-guard-pattern.md).

DO $$
BEGIN
    IF to_regclass('public.external_system_writeback_journal') IS NOT NULL THEN
        -- Drop the old constraint (idempotent — IF EXISTS avoids churn
        -- on fresh DBs where the constraint arrived with the new name).
        ALTER TABLE external_system_writeback_journal
            DROP CONSTRAINT IF EXISTS ck_writeback_journal_status;

        -- Recreate with SKIPPED_INACTIVE added. Keep every pre-I3 value so
        -- any in-flight rows at migration time remain valid.
        ALTER TABLE external_system_writeback_journal
            ADD CONSTRAINT ck_writeback_journal_status
            CHECK (status IN ('PENDING', 'OK', 'SKIPPED', 'FAILED', 'SKIPPED_INACTIVE'));

        RAISE NOTICE 'V136 extended ck_writeback_journal_status with SKIPPED_INACTIVE (I3 follow-up)';
    END IF;
END $$;
