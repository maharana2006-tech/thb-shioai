-- Sprint 51 follow-up BS-M3 (full fix) — per-row scope filter on audit_log.
--
-- Sprint 51 shipped an interim ADMIN-only guard on the audit-log list
-- endpoint because there was no tenant column to filter on. This
-- migration adds `client_code` and backfills existing rows by joining
-- audit_log.actor (username) to users.username; the service layer then
-- filters SELECTs by the caller's scope so USER-tenants see only their
-- own tenant's rows and the interim ADMIN-only gate can be removed.
--
-- Rows with actor IS NULL (system-initiated events, background jobs,
-- migrations) get client_code = NULL. NULL is treated as a platform-wide
-- event visible to ADMINs only — tenant-scoped SELECTs won't match on it.
--
-- Follows docs/flyway-fresh-db-guard-pattern.md — audit_log is auto-
-- created by Hibernate from AuditLog.java, so on a fresh DB Flyway runs
-- before the table exists. Every statement below is wrapped in a
-- to_regclass DO-block guard; Hibernate materialises the column from
-- entity metadata on next boot when the migration no-ops.
--
-- Reversal (docs/migration-rollback.md): DROP INDEX IF EXISTS
-- idx_audit_log_client_code; ALTER TABLE audit_log DROP COLUMN IF EXISTS
-- client_code. Safe on either an empty or populated table.
DO $$
BEGIN
    IF to_regclass('public.audit_log') IS NOT NULL THEN
        ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS client_code VARCHAR(64);

        IF to_regclass('public.users') IS NOT NULL THEN
            UPDATE audit_log a
               SET client_code = u.client_code
              FROM users u
             WHERE a.client_code IS NULL
               AND a.actor IS NOT NULL
               AND LOWER(a.actor) = LOWER(u.username)
               AND u.client_code IS NOT NULL;
        END IF;

        CREATE INDEX IF NOT EXISTS idx_audit_log_client_code
            ON audit_log (client_code);
    END IF;
END $$;
