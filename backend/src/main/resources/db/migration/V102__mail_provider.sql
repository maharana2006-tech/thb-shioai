-- A4.1 — DB-driven mail provider registry.
--
-- Two tables mirror the external_systems pattern (parent row + child KV):
--   mail_provider — one row per registered provider (kind + display name +
--                    active flag). At most ONE row may be active at a time,
--                    enforced by the partial unique index below. Ops pick
--                    the active provider from /settings/mail; ConfiguredMailSender
--                    (Java) reads whichever row is is_active=TRUE at send time.
--   mail_config   — per-provider key/value settings. Secrets (SMTP password,
--                    SendGrid API key, etc.) live in encrypted_value and are
--                    round-tripped through CryptoService; non-secrets in
--                    config_value plaintext. is_secret discriminates.
--
-- Follow-up phases A4.2..A4.5 add notification_template, notification_delivery_log,
-- notification_subscription. This migration only carries the provider config.

CREATE TABLE mail_provider (
    id            BIGSERIAL PRIMARY KEY,
    kind          VARCHAR(20)  NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by    VARCHAR(120)
);

-- Business rule: at most one active provider at a time. Partial unique
-- index means we can have any number of inactive rows and exactly one
-- active row — no app-side coordination needed.
CREATE UNIQUE INDEX ux_mail_provider_active_one
    ON mail_provider (is_active) WHERE is_active = TRUE;

CREATE INDEX ix_mail_provider_kind ON mail_provider (kind);

CREATE TABLE mail_config (
    id               BIGSERIAL PRIMARY KEY,
    provider_id      BIGINT       NOT NULL REFERENCES mail_provider(id) ON DELETE CASCADE,
    config_key       VARCHAR(60)  NOT NULL,
    config_value     TEXT,
    encrypted_value  TEXT,
    is_secret        BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(120),
    CONSTRAINT ux_mail_config_provider_key UNIQUE (provider_id, config_key),
    CONSTRAINT ck_mail_config_value_shape
        CHECK ((is_secret = FALSE AND encrypted_value IS NULL)
            OR (is_secret = TRUE  AND config_value IS NULL))
);

CREATE INDEX ix_mail_config_provider ON mail_config (provider_id);
