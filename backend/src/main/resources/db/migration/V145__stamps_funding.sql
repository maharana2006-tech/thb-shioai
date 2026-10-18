-- Every postage top-up of a Stamps.com (SERA) USPS account: who added how
-- much, the balance afterwards, or why Stamps.com refused it. The key is the
-- Idempotency-Key sent to Stamps.com, so a double-click records one row.
CREATE TABLE IF NOT EXISTS stamps_funding (
    id                      BIGSERIAL     PRIMARY KEY,
    carrier_account_ref_id  BIGINT        NOT NULL REFERENCES carrier_account_ref(id) ON DELETE CASCADE,
    amount                  NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)    NOT NULL DEFAULT 'USD',
    status                  VARCHAR(16)   NOT NULL,   -- ADDED | FAILED
    balance_after           NUMERIC(12,2),
    message                 TEXT,
    idempotency_key         VARCHAR(64)   NOT NULL UNIQUE,
    created_by              VARCHAR(100),
    created_at              TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_stamps_funding_account ON stamps_funding (carrier_account_ref_id, created_at DESC);
