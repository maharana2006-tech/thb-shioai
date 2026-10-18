-- BUG-089 R6 — V30 flipped branded_packaging_allowed=false for ground-family
-- services, but V30 ran against empty shipping_service tables on fresh
-- deploys (upstream carrier sync happens post-boot). The rows later synced
-- in with the default TRUE and never got re-flipped. Tester R6 saw UPS
-- Ground still ticked after V155 removed its stale Express packagings.
--
-- Re-apply the ground-family flip. Idempotent: UPDATE is a no-op when the
-- flag is already false (and when the row doesn't exist).
DO $$
BEGIN
    IF to_regclass('public.shipping_service') IS NULL THEN
        RAISE NOTICE 'V161 skipped — shipping_service missing (fresh DB before first sync)';
        RETURN;
    END IF;

    UPDATE shipping_service SET branded_packaging_allowed = false
     WHERE carrier = 'FEDEX'
       AND service_code IN ('FEDEX_GROUND', 'GROUND_HOME_DELIVERY')
       AND branded_packaging_allowed = true;

    UPDATE shipping_service SET branded_packaging_allowed = false
     WHERE carrier = 'UPS'
       AND service_code IN ('03', '92')
       AND branded_packaging_allowed = true;

    UPDATE shipping_service SET branded_packaging_allowed = false
     WHERE carrier = 'USPS'
       AND service_code IN ('USPS_GROUND_ADVANTAGE', 'GROUND_ADVANTAGE',
                            'PARCEL_SELECT_GROUND', 'PARCEL_SELECT')
       AND branded_packaging_allowed = true;

    RAISE NOTICE 'V161 re-applied branded_packaging_allowed=false for ground-family services';
END $$;
