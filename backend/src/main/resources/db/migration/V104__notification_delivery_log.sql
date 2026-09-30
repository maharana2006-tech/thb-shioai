-- A4.4 — outbound-email delivery log.
--
-- One row per NotificationService.send attempt. Success + failure both
-- persist so ops can see "did the invite email actually go out?" without
-- hunting logs. The rendered subject + body are stored so a retry can
-- re-send the SAME message (idempotent from the recipient's POV) rather
-- than re-rendering with maybe-different vars.
--
-- Fields worth calling out:
--   template_key   — nullable so an ad-hoc /test-send can log too.
--   provider_kind  — snapshot of the active provider at send time. A
--                    later provider switch doesn't rewrite history.
--   retry_of_id    — self-FK. Nullable on the original attempts; set on
--                    rows produced by /retry so ops can trace chains.
--   latency_ms     — round-trip through mailSender.send, useful for
--                    "why is the invite email slow" investigations.

CREATE TABLE notification_delivery_log (
    id             BIGSERIAL PRIMARY KEY,
    template_key   VARCHAR(60),
    recipient      VARCHAR(255) NOT NULL,
    subject        TEXT NOT NULL,
    body           TEXT NOT NULL,
    status         VARCHAR(10) NOT NULL,
    provider_kind  VARCHAR(20),
    provider_id    BIGINT,
    error_message  TEXT,
    latency_ms     INTEGER,
    retry_of_id    BIGINT REFERENCES notification_delivery_log(id) ON DELETE SET NULL,
    sent_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ndl_status CHECK (status IN ('SENT', 'FAILED'))
);

CREATE INDEX ix_ndl_sent_at        ON notification_delivery_log (sent_at DESC);
CREATE INDEX ix_ndl_template_key   ON notification_delivery_log (template_key);
CREATE INDEX ix_ndl_status         ON notification_delivery_log (status);
CREATE INDEX ix_ndl_recipient      ON notification_delivery_log (recipient);
