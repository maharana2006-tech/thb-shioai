-- Incident 2026-08-19 — force all users through /auth/password/forgot.
-- Run manually against PROD (do NOT commit this to a Flyway migration,
-- as it would fire on every dev's local DB on next boot).
--
-- What this does:
--   1. Nulls every user's `password` hash → any subsequent login attempt
--      hits AuthServiceImpl:445 (bcrypt-compare on a null hash → fails)
--      and returns INVALID_CREDENTIALS with the standard timing-safe
--      compare against DUMMY_BCRYPT_HASH.
--   2. Bumps token_version so any existing JWT is invalidated by the
--      next request (JwtAuthenticationFilter compares against the DB
--      value; mismatch → 401). Users must go through /auth/password/forgot
--      to get a reset email.
--
-- Prereqs — verify BEFORE running:
--   · SMTP is wired (password-reset emails must actually deliver)
--   · The 7 users have valid email addresses in `users.email`
--   · You've queued a comms-team message (see 05-user-breach-notification.md)
--     to go out BEFORE users see their sessions die
--
-- Rollback: none. Passwords cannot be restored — users MUST reset.

BEGIN;

-- Sanity check — should list exactly the 7 affected users.
SELECT id, username, email, deactivated_at IS NULL AS active
  FROM users
 ORDER BY id;

-- Confirmation gate — remove the RAISE EXCEPTION below only after
-- reviewing the SELECT above matches expectations.
DO $$
BEGIN
    RAISE EXCEPTION 'Remove this DO block after verifying the SELECT above lists exactly the users you intend to reset.';
END $$;

-- The actual reset — only unblocked after removing the gate above.
UPDATE users
   SET password = NULL,
       token_version = token_version + 1
 WHERE deactivated_at IS NULL;

-- Also invalidate any half-used password-reset tokens issued during
-- the exposure window (attacker with a stolen hash might have tried
-- the /auth/password/forgot flow to inject their own reset token).
DELETE FROM password_reset_tokens
 WHERE created_at >= '2026-07-21';

-- Also drop any pending email-verify tokens from the same window —
-- same attack vector, different endpoint.
UPDATE users
   SET email_verify_token = NULL,
       email_verify_expires_at = NULL
 WHERE email_verify_token IS NOT NULL
   AND email_verify_expires_at >= '2026-07-21';

-- Verify count matches (7 for the leaked dump, adjust if user count
-- has grown since).
SELECT COUNT(*) AS reset_count
  FROM users
 WHERE password IS NULL AND deactivated_at IS NULL;

COMMIT;
