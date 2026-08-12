-- Sprint 50 Tier 1 Group B — data model.
--
-- Two findings bundled: (#2) widen users.carrier_client_secret so it
-- can hold the enc:v1: prefix + AES-GCM base64 ciphertext; (#4) add
-- first-class per-tenant defaults on clients so ~40 hardcoded USD/LB
-- sites across the codebase can source from the tenant record instead
-- of silently falling back to platform defaults.
--
-- Idempotent (IF NOT EXISTS / ALTER … TYPE) so re-application on an
-- environment where ddl-auto=update already widened the column is a
-- no-op.

-- ---------- Finding #2: encrypt users.carrier_client_secret ----------
-- 255 was ample for a plaintext secret; 512 accommodates the "enc:v1:"
-- sentinel (7 chars) + base64(AES-GCM(plaintext + 12-byte nonce +
-- 16-byte tag)). Postgres VARCHAR widening is metadata-only, no rewrite.
--
-- Sprint 50 PR I: ALTER COLUMN doesn't accept IF EXISTS; wrap in a DO
-- block that checks the table + column exist. Fresh-DB boots skip this
-- entirely; Hibernate's entity metadata already declares 512, so the
-- widened column comes into existence via ddl-auto=update on the next
-- boot.
DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'users' AND column_name = 'carrier_client_secret') THEN
        ALTER TABLE users ALTER COLUMN carrier_client_secret TYPE VARCHAR(512);
    END IF;
END $$;

-- ---------- Finding #4: first-class per-tenant defaults on clients ----------
-- Every column is nullable to preserve zero-downtime deploy. Consumer
-- code (ExternalApiService, CarrierServiceImpl) falls back to the
-- platform hardcode when a client hasn't set its default yet, and the
-- Sprint 50 Tier 1-A UNIT_REQUIRED / CURRENCY_REQUIRED checks fire when
-- BOTH client default and caller-supplied value are missing.
--
-- Once an ops backfill pass populates every active client's defaults,
-- a future migration can add NOT NULL constraints per column.
--
-- Sprint 50 PR I: added IF EXISTS on each ALTER TABLE so fresh-DB boots
-- (Hibernate creates `clients` LATER) skip the ADD COLUMN statements.

ALTER TABLE IF EXISTS clients
    ADD COLUMN IF NOT EXISTS default_currency VARCHAR(3);
ALTER TABLE IF EXISTS clients
    ADD COLUMN IF NOT EXISTS default_weight_unit VARCHAR(4);
ALTER TABLE IF EXISTS clients
    ADD COLUMN IF NOT EXISTS default_dim_unit VARCHAR(4);
ALTER TABLE IF EXISTS clients
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(50);
ALTER TABLE IF EXISTS clients
    ADD COLUMN IF NOT EXISTS default_origin_country VARCHAR(2);

-- Sanity check constraints so bad data can't sneak in via SQL. Length
-- covers 3-letter ISO 4217 currencies, LB/KG/OZ/G weights, IN/CM
-- dimensions, ISO-3166-1 alpha-2 countries. Not enforcing exact
-- enums here — the app's UnitConverter + validation surface those
-- with better error messages.
-- Postgres < 15 doesn't support IF NOT EXISTS on ADD CONSTRAINT — use a
-- DO block so migration re-application on an env that already ran V6 is
-- an idempotent no-op.
--
-- Sprint 50 PR I: outer `to_regclass('clients')` guard added so
-- fresh-DB boots skip the CONSTRAINT check + ADD entirely.
DO $$
BEGIN
    IF to_regclass('clients') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_client_default_currency_len') THEN
            ALTER TABLE clients ADD CONSTRAINT chk_client_default_currency_len
                CHECK (default_currency IS NULL OR length(default_currency) = 3);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_client_default_origin_len') THEN
            ALTER TABLE clients ADD CONSTRAINT chk_client_default_origin_len
                CHECK (default_origin_country IS NULL OR length(default_origin_country) = 2);
        END IF;
    END IF;
END $$;
