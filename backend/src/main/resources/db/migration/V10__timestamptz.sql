-- Sprint 51 medium AC-M4 — widen naive TIMESTAMP columns to TIMESTAMPTZ.
--
-- Historically these columns landed as plain TIMESTAMP (no zone) because
-- Hibernate maps `LocalDateTime` to `TIMESTAMP WITHOUT TIME ZONE` by
-- default. That's ambiguous once the app runs in more than one zone
-- (containers on UTC, dev machines on local time, ops on a laptop in
-- another region): "2026-08-14 12:00" could be Bengaluru noon or UTC
-- noon depending on who wrote it.
--
-- Fix in place: convert each column to TIMESTAMPTZ assuming the existing
-- bytes are UTC (matches Spring's default and matches Jackson
-- `spring.jackson.time-zone=UTC`). Deploy runbook expects the container
-- JVM to run with TZ=UTC so the JDBC session's default zone matches;
-- BI / psql users now get zone-aware columns explicitly. We deliberately
-- do NOT set `hibernate.jdbc.time_zone=UTC` because it makes Hibernate 6
-- emit TIMESTAMPTZ on schema-generation for every LocalDateTime field
-- (not just the ones we migrated), which regresses the smoke test's
-- create-drop path. See the companion note in application.properties.
--
-- Follows docs/flyway-fresh-db-guard-pattern.md: every ALTER is wrapped in
-- a to_regclass DO block AND an information_schema column-existence check.
-- On a fresh Postgres this migration is a no-op — Hibernate creates the
-- tables afterwards with LocalDateTime→TIMESTAMP (unchanged).
--
-- The column list comes from V2 (system_settings), V3 (api_key + user_invites +
-- signup_attempts), V4 (users email verify), V5 (users deactivated + user_admin_audit).
-- Deliberately narrow — every other created_at/updated_at across the schema
-- keeps its current type; a broader sweep is a follow-up sprint once the
-- pattern here is proven safe in prod.
--
-- Reversal (see docs/migration-rollback.md): each column can be flipped
-- back with `ALTER TABLE t ALTER COLUMN c TYPE TIMESTAMP USING c AT TIME ZONE 'UTC'`;
-- the AT TIME ZONE conversion is lossless in both directions.

-- Helper: for a given (table, column) pair, convert to TIMESTAMPTZ if the
-- table + column exist and are not already TIMESTAMPTZ. Written inline
-- per column because Postgres has no plpgsql-neutral way to parametrise a
-- DDL identifier at runtime without EXECUTE + format(), and each column
-- has a distinct table name anyway. Idempotent — a re-run on an already
-- converted column no-ops via the data_type filter.

-- ---------- V2: system_settings.updated_at ----------
DO $$
BEGIN
    IF to_regclass('system_settings') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'system_settings'
               AND column_name = 'updated_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE system_settings
            ALTER COLUMN updated_at TYPE TIMESTAMPTZ
            USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------- V3: api_key.expires_at + last_rotated_at ----------
DO $$
BEGIN
    IF to_regclass('api_key') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'api_key'
               AND column_name = 'expires_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE api_key
            ALTER COLUMN expires_at TYPE TIMESTAMPTZ
            USING expires_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('api_key') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'api_key'
               AND column_name = 'last_rotated_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE api_key
            ALTER COLUMN last_rotated_at TYPE TIMESTAMPTZ
            USING last_rotated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------- V3: user_invites.created_at, expires_at, consumed_at ----------
DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'user_invites'
               AND column_name = 'created_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE user_invites
            ALTER COLUMN created_at TYPE TIMESTAMPTZ
            USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'user_invites'
               AND column_name = 'expires_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE user_invites
            ALTER COLUMN expires_at TYPE TIMESTAMPTZ
            USING expires_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'user_invites'
               AND column_name = 'consumed_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE user_invites
            ALTER COLUMN consumed_at TYPE TIMESTAMPTZ
            USING consumed_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------- V3: signup_attempts.created_at ----------
DO $$
BEGIN
    IF to_regclass('signup_attempts') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'signup_attempts'
               AND column_name = 'created_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE signup_attempts
            ALTER COLUMN created_at TYPE TIMESTAMPTZ
            USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------- V4: users.email_verify_expires_at ----------
DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'users'
               AND column_name = 'email_verify_expires_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE users
            ALTER COLUMN email_verify_expires_at TYPE TIMESTAMPTZ
            USING email_verify_expires_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------- V5: users.deactivated_at ----------
DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'users'
               AND column_name = 'deactivated_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE users
            ALTER COLUMN deactivated_at TYPE TIMESTAMPTZ
            USING deactivated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------- V5: user_admin_audit.created_at ----------
DO $$
BEGIN
    IF to_regclass('user_admin_audit') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'user_admin_audit'
               AND column_name = 'created_at'
               AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE user_admin_audit
            ALTER COLUMN created_at TYPE TIMESTAMPTZ
            USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;
