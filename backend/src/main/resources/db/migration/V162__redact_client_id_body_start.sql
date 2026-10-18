-- BUG-130 R6: V157 masked client_id in old carrier_api_log rows but its
-- form-body regex ([?&]client_id=) only matched when client_id was NOT
-- the first field in the body. FedEx tokens post the body as
-- "client_id=<id>&client_secret=..." with client_id at position 0 — no
-- leading '?' or '&' — so those rows kept the clear id. Add a second
-- pass that catches the start-of-body case.
--
-- Idempotent: after the UPDATE every match has been swapped to '***';
-- a re-run finds nothing to change.
DO $$
BEGIN
    IF to_regclass('public.carrier_api_log') IS NOT NULL THEN
        UPDATE carrier_api_log
           SET request_body = regexp_replace(
                   request_body,
                   '^client_id=[^&\s]*', 'client_id=***')
         WHERE request_body LIKE 'client_id=%';
    END IF;
END $$;
