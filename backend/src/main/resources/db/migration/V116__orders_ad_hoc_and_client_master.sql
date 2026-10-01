-- §4 standalone trio — two ALTERs:
--   - orders.ad_hoc  (Manual F-18): replaces the "MANUAL" literal custNo
--     sentinel for ad-hoc shipments. Co-exists with custNo="MANUAL" during
--     migration; CarrierServiceImpl now sets both so legacy readers that
--     check "MANUAL".equals(custNo) keep working while new readers can
--     prefer order.ad_hoc.
--   - client.is_master_account  (QuickShip F18): reserved column for the
--     wrong-client-scan THB-override. Feature itself is TBD (shioai doesn't
--     ship it today); adding the column now so when the feature lands the
--     flag is already schema-stable.

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NOT NULL THEN
        ALTER TABLE label_batch
            ADD COLUMN IF NOT EXISTS ad_hoc BOOLEAN NOT NULL DEFAULT FALSE;
        -- Backfill: every existing order with custNo='MANUAL' is ad-hoc.
        UPDATE label_batch SET ad_hoc = TRUE WHERE cust_no = 'MANUAL' AND ad_hoc = FALSE;
    END IF;

    IF to_regclass('public.clients') IS NOT NULL THEN
        ALTER TABLE clients
            ADD COLUMN IF NOT EXISTS is_master_account BOOLEAN NOT NULL DEFAULT FALSE;
    ELSIF to_regclass('public.client') IS NOT NULL THEN
        ALTER TABLE client
            ADD COLUMN IF NOT EXISTS is_master_account BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$;
