-- D5 — Stamps SERA prepay-wallet auto top-up.
--
-- One row per Stamps carrier_account_ref that opts into automatic
-- top-ups. StampsTopupService's scheduled poller iterates enabled
-- rows every 30 min, checks the SERA /balance endpoint, and fires
-- /balance/add-funds when available <= threshold.
--
-- Schema is deliberately narrow — F6 (per-tenant admin UI + burn-rate
-- tuning) will layer on top later. Ops seeds rows manually today; the
-- future FE at /settings/carriers > Stamps account edit drawer will
-- upsert.
--
-- Also seeds the low-funds notification template so the poller can
-- alert via the A4.2 template stack without a code change.

CREATE TABLE stamps_topup_policy (
    id                      BIGSERIAL PRIMARY KEY,
    carrier_account_ref_id  BIGINT       NOT NULL UNIQUE
        REFERENCES carrier_account_ref(id) ON DELETE CASCADE,
    threshold_amount        NUMERIC(12,2) NOT NULL,
    topup_amount            NUMERIC(12,2) NOT NULL,
    currency                VARCHAR(3)   NOT NULL DEFAULT 'USD',
    alert_email             VARCHAR(255),
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    last_polled_at          TIMESTAMP,
    last_topped_up_at       TIMESTAMP,
    last_balance            NUMERIC(12,2),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by              VARCHAR(120),
    CONSTRAINT ck_stamps_topup_threshold_positive CHECK (threshold_amount > 0),
    CONSTRAINT ck_stamps_topup_amount_positive CHECK (topup_amount > 0)
);

CREATE INDEX ix_stamps_topup_enabled ON stamps_topup_policy (enabled) WHERE enabled = TRUE;

INSERT INTO notification_template
    (template_key, description, subject_template, body_template, opt_out_allowed) VALUES
    (
        'STAMPS.LOW_FUNDS_ALERT',
        'Stamps balance <= threshold, plus TOPPED_UP / TOPUP_FAILED follow-ups. Vars: accountNumber, available, threshold, currency, action, amount, errorMessage.',
        'Stamps postage balance {{action}} — account {{accountNumber}}',
        'Stamps account {{accountNumber}} balance is {{currency}} {{available}} (threshold {{currency}} {{threshold}}).

Action: {{action}}
{{#if amount}}Top-up amount: {{currency}} {{amount}}
{{/if}}{{#if errorMessage}}Error: {{errorMessage}}
{{/if}}

Review at /settings/carriers if action was TOPUP_FAILED.',
        TRUE
    );
