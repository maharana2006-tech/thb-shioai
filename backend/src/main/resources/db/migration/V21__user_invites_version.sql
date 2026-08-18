-- Audit R2 #380 — @Version optimistic locking on user_invites so two
-- concurrent accepts on the same token can't both create a User row.
--
-- Pre-fix, AuthServiceImpl.acceptInvite reads the invite (VALID), saves
-- the User, then consumes the invite — three operations across two
-- transactions. Two racers both see VALID; first save wins; second
-- fails on unique(username|email) with a confusing 409 that doesn't
-- name the real race.
--
-- With @Version, Hibernate stamps 0 on insert + bumps on every update.
-- The second `consume()` throws OptimisticLockingFailureException →
-- controller shapes as 409 INVITE_ALREADY_USED (same errorCode the
-- non-race path uses, so the FE friendly-error mapping already exists).
--
-- Column defaults to 0 on backfill so existing invite rows enter the
-- version lineage cleanly on next update.
--
-- Follows the fresh-DB Flyway pattern (docs/flyway-fresh-db-guard-pattern.md):
-- guarded by to_regclass so a fresh Postgres skips cleanly and
-- Hibernate materialises the column on the next boot.
DO $$
BEGIN
    IF to_regclass('public.user_invites') IS NOT NULL THEN
        ALTER TABLE user_invites
            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
    END IF;
END $$;
