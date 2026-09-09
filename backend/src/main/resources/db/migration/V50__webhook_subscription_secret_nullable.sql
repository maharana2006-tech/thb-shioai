-- Webhook subscriptions are saved with the HMAC secret envelope-encrypted
-- (secret_encrypted / secret_key_id, see V19) and the legacy plaintext
-- `secret` column set to NULL. The column was still NOT NULL, so every
-- POST /api/v1/webhook-subscriptions failed with a 500
-- ("null value in column secret violates not-null constraint").
-- Guarded by to_regclass so a fresh database (Hibernate creates the table
-- from the entity, which is already nullable) skips cleanly.
DO $$
BEGIN
    IF to_regclass('public.external_webhook_subscription') IS NOT NULL THEN
        ALTER TABLE external_webhook_subscription
            ALTER COLUMN secret DROP NOT NULL;
    END IF;
END $$;
