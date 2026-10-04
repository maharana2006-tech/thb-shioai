-- Sprint 50 Tier 0.5 PR F cleanup — formalise the "every non-ADMIN user
-- MUST have a client_code" rule at the schema level.
--
-- Pre-migration state (V3 populated TENANT; A-H left USER free to be
-- NULL during the rollout soak). Post this file, a USER row with
-- client_code=NULL can't be inserted or updated — the legacy
-- "USER-without-clientCode = operator" branch in AccessScopePolicy
-- becomes dead code (the DB rejects what the app branch guarded for).
--
-- Guarded with a sanity SELECT that RAISEs if any legacy NULL remains,
-- so a half-migrated environment aborts the migration instead of
-- silently backfilling to a wrong default.

DO $$
DECLARE
    offending_count INTEGER;
BEGIN
    IF to_regclass('public.users') IS NULL THEN
        RETURN;
    END IF;

    SELECT COUNT(*) INTO offending_count
    FROM users
    WHERE role IS DISTINCT FROM 'ADMIN'
      AND (client_code IS NULL OR client_code = '');

    IF offending_count > 0 THEN
        RAISE EXCEPTION 'Cannot add chk_users_client_code_required: % non-ADMIN user(s) still have NULL/empty client_code. Backfill via /settings/users before deploying this migration.',
            offending_count;
    END IF;

    -- Idempotent add — PostgreSQL doesn't support IF NOT EXISTS on
    -- ADD CONSTRAINT, so check pg_catalog before issuing it.
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_users_client_code_required'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT chk_users_client_code_required
            CHECK (role = 'ADMIN' OR (client_code IS NOT NULL AND client_code <> ''));
    END IF;
END $$;
