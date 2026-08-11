-- Sprint 50 Tier 0.5 PR A — schema foundation for the auth-hardening bundle.
-- PR A is schema + JWT claim only. PR B-F wire the consumers.
-- Idempotent additions so it runs safely alongside Hibernate's ddl-auto=update.

-- ---------- User↔Client tenant linkage (finding #1) ----------
-- Nullable during rollout. Legacy ADMIN + USER rows keep client_code=NULL
-- (org-wide operator scope for backward-compat); TENANT rows are backfilled
-- from uppercase(username). PR F will NOT-NULL for USER + TENANT once every
-- legacy USER has been assigned a client via the PR E admin UI.
ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS client_code VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_users_client_code
    ON users (UPPER(client_code));

-- Backfill TENANT users from the pre-existing username convention.
-- `OrderAccessEvaluator.tenantIdOf` computed tenant as uppercase(username);
-- copying that into the new column preserves current behavior byte-for-byte.
-- ADMIN + USER stay NULL — those roles are org-wide today; PR E will
-- prompt admins to assign clientCode to each legacy USER over ~3 days
-- before the flag flip.
UPDATE users
   SET client_code = UPPER(username)
 WHERE role = 'TENANT' AND client_code IS NULL;

-- ---------- API key lifecycle (findings #13 + #14) ----------
ALTER TABLE IF EXISTS api_key
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
ALTER TABLE IF EXISTS api_key
    ADD COLUMN IF NOT EXISTS rotated_from_id BIGINT;
ALTER TABLE IF EXISTS api_key
    ADD COLUMN IF NOT EXISTS last_rotated_at TIMESTAMP;

-- Backfill existing keys with a 365-day expiry so PR C's filter doesn't
-- suddenly expire everyone on deploy. Admins have a year to rotate.
UPDATE api_key
   SET expires_at = created_at + INTERVAL '365 days'
 WHERE expires_at IS NULL;

-- ---------- User invites (finding #18: PR D consumer) ----------
CREATE TABLE IF NOT EXISTS user_invites (
    id           BIGSERIAL PRIMARY KEY,
    token        VARCHAR(100) NOT NULL UNIQUE,
    email        VARCHAR(255) NOT NULL,
    client_code  VARCHAR(50)  NOT NULL,
    role         VARCHAR(20)  NOT NULL,
    invited_by   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    consumed_at  TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_invites_email
    ON user_invites (email);

-- ---------- Signup rate-limit window (finding #18: PR D consumer) ----------
CREATE TABLE IF NOT EXISTS signup_attempts (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    ip          VARCHAR(45)  NOT NULL,   -- INET6_ADDRSTRLEN
    succeeded   BOOLEAN      NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_signup_attempts_email_created
    ON signup_attempts (email, created_at);
CREATE INDEX IF NOT EXISTS idx_signup_attempts_ip_created
    ON signup_attempts (ip, created_at);
