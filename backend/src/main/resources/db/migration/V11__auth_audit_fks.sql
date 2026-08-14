-- Sprint 51 medium AC-M5 — referential integrity on auth-audit tables.
--
-- V5 introduced user_admin_audit + V3 introduced user_invites without
-- foreign keys. Orphan rows are already possible today (delete a user,
-- their audit trail keeps pointing at a subject_user_id that no longer
-- resolves). Add the missing FKs so admin queries can join without
-- defensive left-joins and so the ORM/BI layer surfaces bad references
-- immediately.
--
-- Also enforce a CHECK on user_invites.role so future PATCH surfaces
-- can't slip an unexpected role through — the app expects one of
-- ADMIN / USER / TENANT and downstream authz has no fallback branch.
--
-- Follows docs/flyway-fresh-db-guard-pattern.md — every ALTER is guarded
-- by to_regclass on both the referencing and referenced table. Constraint
-- add is guarded via pg_constraint.conname existence to make re-runs safe.
--
-- Reversal (docs/migration-rollback.md): each ALTER TABLE ADD CONSTRAINT
-- reverses cleanly with `ALTER TABLE t DROP CONSTRAINT IF EXISTS name`.

-- ---------- user_admin_audit.subject_user_id -> users(id) ----------
-- RESTRICT: removing a user with audit history must be an explicit ops
-- decision, not a silent cascade — audit trails are the record of "who
-- did what to this account" and should not vanish when the account does.
DO $$
BEGIN
    IF to_regclass('user_admin_audit') IS NOT NULL AND to_regclass('users') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_admin_audit_subject_user') THEN
            ALTER TABLE user_admin_audit
                ADD CONSTRAINT fk_user_admin_audit_subject_user
                FOREIGN KEY (subject_user_id) REFERENCES users (id)
                ON DELETE RESTRICT;
        END IF;
    END IF;
END $$;

-- ---------- user_invites.invited_by -> users(username) ----------
-- The column stores a username (VARCHAR(100), matches users.username's
-- shape and unique-not-null constraint), NOT a numeric user id — the
-- audit trail on invites is human-readable by design. Point the FK at
-- users(username) so a rename or delete surfaces cleanly. RESTRICT so
-- deleting the inviter with pending invites is an explicit ops decision.
DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL AND to_regclass('users') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_invites_invited_by') THEN
            ALTER TABLE user_invites
                ADD CONSTRAINT fk_user_invites_invited_by
                FOREIGN KEY (invited_by) REFERENCES users (username)
                ON DELETE RESTRICT;
        END IF;
    END IF;
END $$;

-- ---------- user_invites.role CHECK ----------
-- The application resolves role via a switch that assumes one of these
-- three values; an unknown value falls through to no-op authz which is
-- worse than a hard fail. Enforce at the schema level so the invariant
-- can't drift via a hand-crafted SQL update.
DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_user_invites_role') THEN
            ALTER TABLE user_invites
                ADD CONSTRAINT chk_user_invites_role
                CHECK (role IN ('ADMIN', 'USER', 'TENANT'));
        END IF;
    END IF;
END $$;
