-- Sprint 51 BS-M4 — password reset flow.
--
-- Stores hashed one-time tokens for the /auth/password/forgot →
-- /auth/password/reset flow. The plaintext token is only ever emailed;
-- what we persist is a SHA-256 hex digest, so an attacker who reads the
-- table can't consume tokens directly. Rows are single-use — the reset
-- endpoint deletes the row on success. Expired rows survive until a
-- cleanup job (out of scope for this migration) purges them via the
-- expires_at index.
--
-- Follows the Sprint 50 PR I fresh-DB pattern: guarded with a
-- to_regclass() DO block so the migration is a no-op on a truly fresh
-- Postgres (Hibernate creates the table via the entity later and Flyway
-- just records the version). See docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL THEN
        CREATE TABLE IF NOT EXISTS password_reset_tokens (
            id           BIGSERIAL PRIMARY KEY,
            user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            token_hash   VARCHAR(255) NOT NULL UNIQUE,
            expires_at   TIMESTAMPTZ NOT NULL,
            created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_at
            ON password_reset_tokens (expires_at);
    ELSE
        RAISE NOTICE 'V12: users table does not exist yet — Hibernate will create password_reset_tokens via the entity (fresh DB path).';
    END IF;
END
$$;
