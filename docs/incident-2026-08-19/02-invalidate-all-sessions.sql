-- Incident 2026-08-19 — kill every active JWT without touching JWT_SECRET.
--
-- Bumping token_version invalidates every existing JWT for the user (the
-- JwtAuthenticationFilter compares the DB value against the JWT's `ver`
-- claim on every request; mismatch → 401). This is preferred over
-- rotating JWT_SECRET because:
--   · JWT_SECRET rotation invalidates EVERY session in one shot, before
--     you can notify users → they hit an unexplained 401
--   · JWT_SECRET rotation requires app restart + coordinated cutover
--   · token_version bump is DB-only, per-user, atomic, and reversible
--     (you can't un-rotate a secret without keeping the old one around)
--
-- If you already ran 01-force-password-reset.sql, this is redundant —
-- that script already bumps token_version. Run this file ONLY if you
-- want the session-kill WITHOUT the password reset (e.g. you're
-- testing containment before deciding to force the full reset).
--
-- Rollback: none needed. Users just re-login; nothing lost.

BEGIN;

-- Preview affected users.
SELECT id, username, token_version AS current_version
  FROM users
 WHERE deactivated_at IS NULL
 ORDER BY id;

-- Confirmation gate.
DO $$
BEGIN
    RAISE EXCEPTION 'Remove this DO block after reviewing the SELECT above.';
END $$;

UPDATE users
   SET token_version = token_version + 1
 WHERE deactivated_at IS NULL;

-- Verify — the RETURNING clause above already emits per-row output;
-- this count is a summary.
SELECT COUNT(*) AS invalidated FROM users WHERE deactivated_at IS NULL;

COMMIT;
