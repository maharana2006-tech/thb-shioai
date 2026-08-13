-- Sprint 51 Tier 2 finding #5 — JWT session revocation.
--
-- Adds `token_version` to `users`. The JWT carries the caller's tv at
-- issue time; JwtAuthenticationFilter rejects any token whose tv is
-- older than the current DB value. Any credential-material change
-- (logout-all, deactivate, role change, future password reset) bumps
-- the column and invalidates every outstanding JWT for that user in
-- a single UPDATE — no per-token blacklist scan required.
--
-- A parallel Redis jti blacklist gives per-device logout
-- ("log this laptop out but leave my phone signed in") without a
-- global bump; both mechanisms compose (see JwtAuthenticationFilter).
--
-- Follows the Sprint 50 PR I fresh-DB pattern: guarded with a
-- to_regclass() DO block so the migration is a no-op on a truly
-- fresh Postgres (Hibernate creates the users table later and
-- Flyway just records the version). See docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL THEN
        -- BIGINT + DEFAULT 0 so existing rows land with tv=0 automatically.
        -- Newly-issued tokens will carry tv=0 as their claim on first login;
        -- subsequent bumps monotonically increment.
        ALTER TABLE users
            ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;
    ELSE
        RAISE NOTICE 'V9: users table does not exist yet — Hibernate will create with tokenVersion default (fresh DB path).';
    END IF;
END
$$;
