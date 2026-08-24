-- F6-B2 (fallback-audit follow-up) — per-account billing currency.
--
-- Adds a nullable currency column to carrier_account_ref so operators can
-- override the "carrier's home currency" default per account. Powers the
-- currency-conversion path (F6-D): when a client currency ≠ this account's
-- currency, FxRateService converts declared value / commodities / insured
-- value / freight before the connector sees the request.
--
-- Semantics of NULL:
--   NULL → resolver falls back to the carrier's hardcoded home currency
--          (USPS/UPS/FedEx → USD; DHL → EUR). This is why we deliberately
--          do NOT backfill existing rows — a DHL account silently flipped
--          to USD would be worse than leaving it NULL and letting the
--          resolver's per-carrier default kick in.
--
-- Non-NULL:
--   ISO 4217 3-letter code (USD, EUR, GBP, JPY, ...). Value taken as the
--   authoritative currency for anything this account bills — regardless of
--   client currency or carrier default. Overrides both.
--
-- Fresh-DB pattern: guarded by to_regclass so a fresh Postgres skips this
-- migration and Hibernate materialises the column from the @Entity on next
-- boot. See docs/flyway-fresh-db-guard-pattern.md.
--
-- No code path reads this column until F6-B3 (resolver) lands, so this
-- migration is zero-risk on its own.
DO $$
BEGIN
    IF to_regclass('public.carrier_account_ref') IS NOT NULL THEN
        -- Nullable + no default so existing rows read as "unset" (resolver
        -- interprets as carrier home currency). Explicit backfill would
        -- silently flip DHL accounts to USD which is wrong; operators add
        -- explicit overrides through the CarrierConnections drawer instead.
        ALTER TABLE carrier_account_ref
            ADD COLUMN IF NOT EXISTS currency CHAR(3);
    END IF;
END $$;
