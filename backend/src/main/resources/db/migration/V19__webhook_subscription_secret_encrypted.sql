-- Audit R2 #336 — HMAC secrets at rest.
--
-- Pre-fix, `external_webhook_subscription.secret` held the HMAC signing
-- key as plaintext VARCHAR. A DB dump / backup = every tenant's webhook
-- signing key leaked. The DTO already masks the field on read (`••••••`)
-- but the row itself was cleartext.
--
-- Two new columns for envelope-encrypted storage:
--   secret_encrypted VARCHAR(1024) — base64(nonce || ciphertext || tag)
--                                    from CryptoService.encrypt().
--   secret_key_id    SMALLINT      — key generation id (always 1 today;
--                                    future rotation adds 2/3/... and a
--                                    background job re-encrypts old rows).
--
-- Both nullable. Existing `secret` stays as-is for one release so the
-- dispatcher can fall back to plaintext during the transition (any save
-- moves the row to encrypted form; a future PR drops the plaintext
-- column once all rows are migrated).
--
-- Follows the fresh-DB Flyway pattern (docs/flyway-fresh-db-guard-pattern.md):
-- guarded by to_regclass so a fresh Postgres with no table yet skips
-- cleanly and Hibernate materialises both new columns on the next boot.
DO $$
BEGIN
    IF to_regclass('public.external_webhook_subscription') IS NOT NULL THEN
        ALTER TABLE external_webhook_subscription
            ADD COLUMN IF NOT EXISTS secret_encrypted VARCHAR(1024);
        ALTER TABLE external_webhook_subscription
            ADD COLUMN IF NOT EXISTS secret_key_id SMALLINT;
    END IF;
END $$;
