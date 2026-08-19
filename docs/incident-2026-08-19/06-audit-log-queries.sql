-- Incident 2026-08-19 — investigation queries for the exposure window.
-- Run against PROD. Read-only — safe to run repeatedly.
--
-- Window: 2026-07-21 (first dump created) → NOW (or the date you
-- complete the rotation and can rule out further ongoing abuse).
--
-- The goal is to find evidence that the leaked hashes / carrier
-- credentials were actually used by an attacker, not just exposed.

-- ─── Failed-login spikes (brute-force attempts on the leaked hashes) ─────

-- Per-user daily failed-login counts.
SELECT DATE_TRUNC('day', created_at) AS day,
       actor AS username,
       COUNT(*) AS failed_attempts
  FROM audit_log
 WHERE action = 'LOGIN_FAILED'
   AND created_at >= '2026-07-21'
 GROUP BY day, actor
 ORDER BY failed_attempts DESC, day DESC
 LIMIT 200;

-- Any single day where a user had more than ~20 failed logins is
-- suspicious (normal user typos < 5/day). Above ~100/day is almost
-- certainly an offline-hash-crack + online-verify pattern.

-- ─── Successful logins from unusual sources ───────────────────────────────

-- Every login (LOGIN_OK) since the exposure window, ordered by user.
-- Look for successes from IPs that user has never logged in from before,
-- OR from unusual geographies (compare vs. AuditLog.notes which often
-- carries the IP).
SELECT created_at,
       actor,
       action,
       notes  -- typically includes source IP + user-agent
  FROM audit_log
 WHERE action IN ('LOGIN_OK', 'LOGIN_FAILED')
   AND created_at >= '2026-07-21'
 ORDER BY actor, created_at DESC
 LIMIT 500;

-- ─── Password-reset abuse (attacker trying to hijack via /auth/password/forgot) ─

SELECT created_at, actor, action, notes
  FROM audit_log
 WHERE action IN ('PASSWORD_RESET_REQUEST', 'PASSWORD_RESET_CONSUMED')
   AND created_at >= '2026-07-21'
 ORDER BY created_at DESC;

-- Legitimate users rarely request more than 1 reset per day. Multiple
-- requests for the same user (especially without a consume) look like
-- an attacker DoS'ing the mailbox or probing the endpoint.

-- ─── Carrier-account activity (API abuse) ─────────────────────────────────

-- Label-generation counts per day per user. A spike from a user who
-- normally generates <10 labels/day to hundreds/thousands could
-- indicate a stolen bearer token being used to burn the carrier's
-- rate limit or exfiltrate account state.
SELECT DATE_TRUNC('day', created_at) AS day,
       actor,
       COUNT(*) AS label_count
  FROM audit_log
 WHERE action IN ('LABEL_GENERATED', 'BULK_LABEL_JOB_SUBMITTED')
   AND created_at >= '2026-07-21'
 GROUP BY day, actor
 ORDER BY label_count DESC
 LIMIT 100;

-- Void spikes (attacker voiding labels to disrupt operations)
SELECT DATE_TRUNC('day', created_at) AS day,
       actor,
       COUNT(*) AS void_count
  FROM audit_log
 WHERE action = 'LABEL_VOIDED'
   AND created_at >= '2026-07-21'
 GROUP BY day, actor
 ORDER BY void_count DESC
 LIMIT 100;

-- ─── Admin actions (privilege escalation attempts) ────────────────────────

SELECT created_at, actor, action, entity_type, entity_id, notes
  FROM audit_log
 WHERE action IN ('USER_ROLE_CHANGED', 'USER_ACTIVATED', 'USER_DEACTIVATED',
                  'API_KEY_ISSUED', 'API_KEY_ROTATED', 'CARRIER_ACCOUNT_UPSERTED')
   AND created_at >= '2026-07-21'
 ORDER BY created_at DESC;

-- Any USER_ROLE_CHANGED where a non-ADMIN was elevated to ADMIN during
-- the window and hasn't been explained is a red flag.

-- ─── API-key issuance (attacker minting long-lived tokens) ────────────────

-- Any api_key rows created during the window with the exposed users'
-- credentials would be a smoking gun.
SELECT id, name, client_code, environment, created_at, created_by, expires_at
  FROM api_key
 WHERE created_at >= '2026-07-21'
 ORDER BY created_at DESC;

-- Cross-reference: any api_key here that wasn't created via a
-- legitimate ticket → revoke immediately with:
--   UPDATE api_key SET active = false, revoked_at = now() WHERE id = <n>;

-- ─── Anything with the actor 'unknown' during the window ──────────────────

-- Legitimate anon actions (public /auth/signup, /auth/password/forgot)
-- record actor='anon'. Anything else with a null/'unknown' actor
-- during the window is worth eyeballing.
SELECT created_at, action, entity_type, notes
  FROM audit_log
 WHERE (actor IS NULL OR actor IN ('unknown', 'system'))
   AND action NOT IN ('SIGNUP_ATTEMPT', 'PASSWORD_RESET_REQUEST')
   AND created_at >= '2026-07-21'
 ORDER BY created_at DESC
 LIMIT 200;

-- ─── Rate-shop / tracking API from unauthenticated api_key ────────────────

-- If any api_key holder started using the API at a much higher rate
-- during the window, that's worth checking.
SELECT DATE_TRUNC('day', created_at) AS day,
       actor,
       action,
       COUNT(*) AS calls
  FROM audit_log
 WHERE actor LIKE 'apikey:%'
   AND created_at >= '2026-07-21'
 GROUP BY day, actor, action
HAVING COUNT(*) > 50
 ORDER BY calls DESC;
