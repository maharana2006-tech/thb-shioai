-- BUG-126 — the billing-account rule referred to a menu item ("Settings
-- -> Carriers") that no longer exists. The actual menu options are
-- "Carrier Accounts" and "Carriers (platform)". Rewrite the message.
--
-- Idempotent: WHERE clause filters the exact pre-fix string.
DO $$
BEGIN
    IF to_regclass('public.carrier_error_message') IS NOT NULL THEN
        UPDATE carrier_error_message
           SET humanized = '{carrier} rejected the billing account. The account isn''t authorised for this carrier - check Settings -> Carrier Accounts.',
               updated_at = NOW()
         WHERE humanized LIKE '%Settings%Carriers.%'
            OR humanized LIKE '%check Settings%Carriers%';
    END IF;
END $$;
