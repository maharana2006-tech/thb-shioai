-- BUG-162 R7: 915 carrier_api_log rows (ids 1..1579, Oct 8-11) stored
-- carrier='UNKNOWN' because they predate CarrierApiLoggingInterceptor's
-- host-based label. Every one of them has a URL on the Stamps SERA host
-- (api.testing.stampsendicia.com or signin.testing.stampsendicia.com),
-- so the host is a reliable classifier.
--
-- Idempotent: WHERE carrier='UNKNOWN' narrows to the backlog; a re-run
-- updates zero rows after the first apply.
DO $$
BEGIN
    IF to_regclass('public.carrier_api_log') IS NULL THEN
        RAISE NOTICE 'V164 skipped — carrier_api_log missing';
        RETURN;
    END IF;

    UPDATE carrier_api_log
       SET carrier = 'STAMPS_COM'
     WHERE carrier = 'UNKNOWN'
       AND url ILIKE '%stampsendicia.com%';

    -- Future-proof: catch any legacy Stamps.com rows that used the
    -- original stamps.com host too.
    UPDATE carrier_api_log
       SET carrier = 'STAMPS_COM'
     WHERE carrier = 'UNKNOWN'
       AND url ILIKE '%stamps.com%';
END $$;
