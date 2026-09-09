-- Stamps.com SERA 3-legged OAuth (authorization_code + refresh_token).
--
-- Stamps.com developer accounts are provisioned for authorization_code
-- flow, NOT client_credentials. The prior SERA "verify credentials" path
-- posted grant_type=client_credentials and Auctane returned
-- unsupported_grant_type. Real fix: launch the operator through the
-- browser-redirect flow, capture code, exchange for access_token +
-- refresh_token, then persist the refresh_token on the account.
--
-- Column shape mirrors client_secret: TEXT (base64 nonce||GCM
-- ciphertext||tag can exceed the client_secret 512-char cap for
-- long-lived tokens), encrypted at rest via EncryptedStringConverter
-- (same enc:v1: sentinel + AES-GCM as SystemSetting / client_secret).
--
-- Never backfilled — legacy accounts using SWSIM don't have refresh
-- tokens and don't need them. Column is nullable so the SWSIM path
-- keeps operating on rows without one.
ALTER TABLE carrier_account_ref
    ADD COLUMN IF NOT EXISTS stamps_refresh_token TEXT;
