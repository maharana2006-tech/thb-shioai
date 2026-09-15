-- V58 — USPS_DIRECT integration: per-account identifiers.
--
-- Adds three plaintext identifier columns to carrier_account_ref for the
-- USPS_DIRECT integration path (developers.usps.com OAuth 2.0). These are
-- NOT secrets — they are account-level identifiers issued by USPS EPS and
-- Business Customer Gateway:
--
--   usps_direct_account_number — USPS EPS (Enterprise Payment System)
--       account number that funds postage. Distinct from the internal
--       carrier_account_ref.account_number (which is the platform's row
--       key). Nullable during PROVISIONING_USPS_DIRECT, required before
--       USPS_PROVIDER transitions to USPS_DIRECT.
--   usps_direct_crid — Customer Registration ID from USPS Business
--       Customer Gateway. Identifies the business entity across USPS
--       services. Same nullability shape as the EPS account number.
--   usps_direct_mid — Mailer ID assigned by USPS. Distinct from CRID:
--       CRID is the customer, MID is a mailing identity that can be
--       one-to-many under a CRID. Populated during onboarding.
--
-- All three plaintext — public identifiers, not credentials. The OAuth
-- consumer key + secret live in system_setting (USPS_PLATFORM_CLIENT_ID /
-- USPS_PLATFORM_CLIENT_SECRET) — those are platform-level, not per-account.
--
-- Fresh-DB safe: guarded with to_regclass + information_schema column
-- checks per docs/flyway-fresh-db-guard-pattern.md. Hibernate creates the
-- table (nullable columns match the entity that Agent C will land) on a
-- truly fresh DB, so this migration is a no-op there.

DO $$
BEGIN
    IF to_regclass('public.carrier_account_ref') IS NOT NULL THEN
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'carrier_account_ref'
                   AND column_name = 'usps_direct_account_number') THEN
            ALTER TABLE public.carrier_account_ref
                ADD COLUMN usps_direct_account_number VARCHAR(50);
            COMMENT ON COLUMN public.carrier_account_ref.usps_direct_account_number IS
                'USPS EPS account number (Enterprise Payment System). Plaintext identifier, not a secret. Required before USPS_PROVIDER can transition to USPS_DIRECT.';
        END IF;

        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'carrier_account_ref'
                   AND column_name = 'usps_direct_crid') THEN
            ALTER TABLE public.carrier_account_ref
                ADD COLUMN usps_direct_crid VARCHAR(20);
            COMMENT ON COLUMN public.carrier_account_ref.usps_direct_crid IS
                'USPS Customer Registration ID from Business Customer Gateway. Plaintext identifier, not a secret. Identifies the business entity across USPS services.';
        END IF;

        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'carrier_account_ref'
                   AND column_name = 'usps_direct_mid') THEN
            ALTER TABLE public.carrier_account_ref
                ADD COLUMN usps_direct_mid VARCHAR(20);
            COMMENT ON COLUMN public.carrier_account_ref.usps_direct_mid IS
                'USPS Mailer ID (MID). Plaintext identifier, not a secret. May be one-to-many under a CRID.';
        END IF;
    END IF;
END $$;
