-- V59 — USPS_DIRECT integration PR-B: subscription persistence.
--
-- One row per USPS subscription this platform has registered with USPS'
-- Subscriptions-Tracking v3 API to receive push tracking events. USPS
-- pushes events at listener_url signed with an HMAC secret we minted at
-- create-time; that secret is envelope-encrypted at rest via the same
-- CryptoService (AES-GCM base64(nonce||cipher||tag)) that guards
-- system_setting, external_webhook_subscription.secret_encrypted, and
-- carrier_account_ref.client_secret.
--
-- filter_type values:
--   MID              — subscribe to every event for a given Mailer ID.
--   TRACKING_NUMBERS — subscribe to a fixed list of tracking numbers
--                      (filter_value is a CSV list).
--   STID             — subscribe by USPS Service Type ID.
-- filter_value carries the corresponding scalar / CSV for that type.
--
-- Rows are never physically deleted; a delete flips status=DELETED +
-- stamps deleted_at so we can audit what USPS was told + reconcile if
-- the DELETE against USPS' side eventually 404s. The unique constraint
-- on usps_subscription_id guarantees at-most-one live row per USPS-side
-- subscription; if USPS ever re-uses an id after a delete the create
-- path detects the collision and rejects.
--
-- Fresh-DB safe: CREATE TABLE / CREATE INDEX both use IF [NOT] EXISTS
-- and reference only the new table (see docs/flyway-fresh-db-guard-pattern.md).

CREATE TABLE IF NOT EXISTS usps_direct_subscription (
    id                   BIGSERIAL PRIMARY KEY,
    usps_subscription_id VARCHAR(100) NOT NULL UNIQUE,
    filter_type          VARCHAR(20)  NOT NULL,
    filter_value         TEXT         NOT NULL,
    listener_url         VARCHAR(500) NOT NULL,
    secret_encrypted     TEXT,
    event_types          VARCHAR(200),
    environment          VARCHAR(20)  NOT NULL DEFAULT 'PRODUCTION',
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMP
);

COMMENT ON TABLE usps_direct_subscription IS
    'USPS Subscriptions-Tracking v3 registrations this platform manages. See docs/usps-direct-integration.md PR-B.';
COMMENT ON COLUMN usps_direct_subscription.usps_subscription_id IS
    'Subscription id returned by USPS on POST /subscriptions-tracking/v3/subscriptions. Unique across the platform.';
COMMENT ON COLUMN usps_direct_subscription.filter_type IS
    'MID | TRACKING_NUMBERS | STID — matches USPS filterProperties keys.';
COMMENT ON COLUMN usps_direct_subscription.filter_value IS
    'Scalar (MID / STID) or CSV list (tracking numbers) — parsed based on filter_type.';
COMMENT ON COLUMN usps_direct_subscription.secret_encrypted IS
    'HMAC signing secret USPS uses on push events. Envelope-encrypted at rest via CryptoService (same wire format as system_setting).';
COMMENT ON COLUMN usps_direct_subscription.event_types IS
    'CSV of eventTypes[] this subscription is registered for. NULL = every event USPS ships.';
COMMENT ON COLUMN usps_direct_subscription.environment IS
    'PRODUCTION (apis.usps.com) | SANDBOX (apis-tem.usps.com). Same convention as UspsOAuthTokenCache.';
COMMENT ON COLUMN usps_direct_subscription.status IS
    'ACTIVE | DELETED. Rows are never physically removed so we can reconcile against USPS'' side.';

-- Filter lookups from the webhook parse path — given an incoming event''s
-- MID (or matched tracking number / STID), find the subscription row that
-- registered it so we can resolve the HMAC secret for verification.
CREATE INDEX IF NOT EXISTS idx_usps_subscription_filter
    ON usps_direct_subscription (filter_type, filter_value);
