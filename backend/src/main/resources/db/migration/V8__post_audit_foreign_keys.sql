-- Sprint 50 post-audit foreign keys — greenfield deployment.
--
-- The V7 post-audit indexes migration explicitly deferred the referential
-- integrity work (findings #2, #3-self-FK, #4, #19) because on a legacy
-- database any orphan rows would fail ADD CONSTRAINT and block the boot.
--
-- This deployment is GREENFIELD — no prod, no legacy data — so those
-- constraints are safe to add now. On an empty table, ADD CONSTRAINT
-- always passes its validation scan. Should a future environment carry
-- legacy data, the defensive NOT NULL check below will warn (not block)
-- via RAISE NOTICE, giving ops a signal without breaking migration.
--
-- Follows the fresh-DB pattern in docs/flyway-fresh-db-guard-pattern.md:
-- every reference to a Hibernate-managed table is wrapped in a
-- `to_regclass(...)` DO block so this migration is a no-op on a truly
-- fresh Postgres (Hibernate creates the tables afterwards, Flyway just
-- records the version). Constraint idempotency mirrors V6's pattern —
-- `IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ...)`.

-- ---------- Finding #2: api_key.client_code -> clients.client_code ----------
-- Every api_key row logically belongs to a tenant; without the FK, a
-- typo or a stale delete could leave orphan keys authenticating against
-- a non-existent client. RESTRICT on delete: removing a client with
-- active API keys must be an explicit ops decision, not a silent cascade.
DO $$
BEGIN
    IF to_regclass('api_key') IS NOT NULL AND to_regclass('clients') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_api_key_client_code') THEN
            ALTER TABLE api_key
                ADD CONSTRAINT fk_api_key_client_code
                FOREIGN KEY (client_code) REFERENCES clients (client_code)
                ON DELETE RESTRICT;
        END IF;
    END IF;
END $$;

-- ---------- Finding #19: api_key.client_code NOT NULL ----------
-- The application layer already treats client_code as required (every
-- write path sets it). Enforce at the schema level so future codepaths
-- can't slip a NULL through. On greenfield the table is empty and the
-- constraint applies trivially; on a legacy DB we log a NOTICE with the
-- orphan count instead of blocking migration — ops can then backfill
-- and re-apply.
DO $$
DECLARE
    null_count BIGINT;
BEGIN
    IF to_regclass('api_key') IS NOT NULL THEN
        SELECT COUNT(*) INTO null_count FROM api_key WHERE client_code IS NULL;
        IF null_count > 0 THEN
            RAISE NOTICE 'V8: api_key has % rows with NULL client_code; skipping NOT NULL enforcement. Backfill and re-apply.', null_count;
        ELSE
            -- SET NOT NULL has no IF NOT EXISTS clause, but is idempotent:
            -- re-running on an already-NOT NULL column is a no-op.
            ALTER TABLE api_key ALTER COLUMN client_code SET NOT NULL;
        END IF;
    END IF;
END $$;

-- ---------- Finding #3 (self-FK part): api_key.rotated_from_id -> api_key.id ----------
-- V7 added the partial index for the rotation-sweep query but left the
-- self-referential FK for this migration. SET NULL on delete: if the
-- historical "rotated from" row is pruned, the current key should keep
-- functioning with a NULL predecessor rather than cascading its own
-- delete.
DO $$
BEGIN
    IF to_regclass('api_key') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_api_key_rotated_from') THEN
            ALTER TABLE api_key
                ADD CONSTRAINT fk_api_key_rotated_from
                FOREIGN KEY (rotated_from_id) REFERENCES api_key (id)
                ON DELETE SET NULL;
        END IF;
    END IF;
END $$;

-- ---------- Finding #4: user_invites.client_code -> clients.client_code ----------
-- Same rationale as finding #2: invites are per-tenant and must point at
-- a real client. RESTRICT so deleting a client with pending invites is
-- an explicit ops decision. Also adds the covering index — no prior
-- migration created one and the FK lookup benefits.
DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL AND to_regclass('clients') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_invites_client_code') THEN
            ALTER TABLE user_invites
                ADD CONSTRAINT fk_user_invites_client_code
                FOREIGN KEY (client_code) REFERENCES clients (client_code)
                ON DELETE RESTRICT;
        END IF;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_user_invites_client_code
            ON user_invites (client_code);
    END IF;
END $$;
