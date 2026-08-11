-- Sprint 50 Tier 0.5 PR D — invite-only signup + email verification.
-- Idempotent additions so it runs safely alongside Hibernate's ddl-auto=update.

-- ---------- Email verification (finding #18) ----------
-- Legacy rows backfill to TRUE so existing operators aren't locked out
-- on deploy. New signup rows land as FALSE until the verification email
-- is consumed (or the invite-accept path bypasses since the invite
-- itself is the verification).
ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS email_verify_token VARCHAR(100);

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS email_verify_expires_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_users_email_verify_token
    ON users (email_verify_token);
