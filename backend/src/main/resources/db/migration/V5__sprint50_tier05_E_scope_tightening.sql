-- Sprint 50 Tier 0.5 PR E — tenant-scope tightening.
-- Adds user deactivation + client-assignment audit trail.
-- Idempotent so it runs safely alongside Hibernate ddl-auto=update.

-- ---------- User deactivation (soft delete) ----------
-- Set when an admin revokes a user via the /settings/users page.
-- Login checks `deactivated_at IS NULL` and rejects with FORBIDDEN
-- when set, so a revoked account cannot obtain a fresh JWT even if
-- the old one is expired.
ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMP;

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS deactivated_by VARCHAR(100);

-- ---------- Client-assignment audit trail (finding #1 admin surface) ----------
-- One row per PATCH /admin/users/{id}/client. Kept lean intentionally —
-- the point is "who moved this user to this client, and when" so an ops
-- lead can spot a mis-assignment quickly. Deactivate/reactivate events
-- also flow here with action='DEACTIVATE' / 'REACTIVATE' and
-- new_client_code NULL.
CREATE TABLE IF NOT EXISTS user_admin_audit (
    id                BIGSERIAL PRIMARY KEY,
    subject_user_id   BIGINT       NOT NULL,
    subject_username  VARCHAR(100) NOT NULL,
    action            VARCHAR(32)  NOT NULL,
    old_client_code   VARCHAR(50),
    new_client_code   VARCHAR(50),
    actor_username    VARCHAR(100) NOT NULL,
    reason            VARCHAR(500),
    created_at        TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_admin_audit_subject
    ON user_admin_audit (subject_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_admin_audit_created
    ON user_admin_audit (created_at DESC);
