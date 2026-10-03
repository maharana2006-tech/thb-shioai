-- A4.5 — per-user × per-template email subscriptions.
--
-- Two changes:
--   1. notification_template gains opt_out_allowed. Transactional emails
--      (invite, verify, password reset) leave it FALSE — you can't opt
--      out of "you have a password to reset". Alert templates
--      (OPS.LOW_FUNDS, MARKETING.DIGEST, etc.) can set it TRUE.
--   2. notification_subscription — one row per (user, template) pair
--      where the user has explicitly toggled. Absent row = default
--      subscribed (industry norm for transactional apps).
--
-- The gate in NotificationService only checks subscription when the
-- template's opt_out_allowed is TRUE AND a matching user_id can be
-- resolved from the recipient email. Otherwise send unconditionally.

ALTER TABLE notification_template
    ADD COLUMN opt_out_allowed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE notification_subscription (
    user_id        BIGINT       NOT NULL,
    template_key   VARCHAR(60)  NOT NULL,
    enabled        BOOLEAN      NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by     VARCHAR(120),
    PRIMARY KEY (user_id, template_key),
    CONSTRAINT fk_ns_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ns_template FOREIGN KEY (template_key)
        REFERENCES notification_template(template_key) ON DELETE CASCADE
);

CREATE INDEX ix_ns_template_key ON notification_subscription (template_key);
