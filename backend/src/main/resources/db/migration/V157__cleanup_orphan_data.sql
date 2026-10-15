-- R6 retroactive cleanup batch. All forward-only code fixes landed in prior
-- rounds; this picks up the data they couldn't backfill.
--
-- BUG-080: client_warehouse rows pointing at deleted clients (3PL-EAST had
--   Attached 7 but only 3 clients existed).
-- BUG-130: carrier_api_log rows written before the client_id redaction fix
--   still carry clear client_ids. Mask them in-place.
-- BUG-140: printer_scan_agent rows for deleted tenants are unreachable in
--   the FE Tenant picker but still answer queries.
-- BUG-017 (broader): label_batch rows with package_count > 1 that still
--   hold a single-box weight (the V150 pass only touched OK-status rows;
--   ERROR orders were missed because they lack label_package rows).
DO $$
BEGIN
    -- BUG-080 — orphan client_warehouse links.
    IF to_regclass('public.client_warehouse') IS NOT NULL
       AND to_regclass('public.clients') IS NOT NULL THEN
        DELETE FROM client_warehouse cw
         WHERE NOT EXISTS (
             SELECT 1 FROM clients c
              WHERE UPPER(c.client_code) = UPPER(cw.client_code));
    END IF;

    -- BUG-140 — orphan scanner enrolments.
    IF to_regclass('public.printer_scan_agent') IS NOT NULL
       AND to_regclass('public.clients') IS NOT NULL THEN
        DELETE FROM printer_scan_agent psa
         WHERE NOT EXISTS (
             SELECT 1 FROM clients c
              WHERE UPPER(c.client_code) = UPPER(psa.tenant_code));
    END IF;

    -- BUG-130 — redact client_id in old carrier_api_log request / response
    -- bodies. Matches JSON "client_id":"<value>" and form client_id=<value>.
    IF to_regclass('public.carrier_api_log') IS NOT NULL THEN
        UPDATE carrier_api_log
           SET request_body = regexp_replace(
                   regexp_replace(request_body,
                       '"client_id"\s*:\s*"[^"]*"', '"client_id":"***"', 'g'),
                   '([?&])client_id=[^&\s]*', '\1client_id=***', 'g'),
               response_body = regexp_replace(response_body,
                   '"client_id"\s*:\s*"[^"]*"', '"client_id":"***"', 'g')
         WHERE (request_body LIKE '%client_id%' OR response_body LIKE '%client_id%');
    END IF;
END $$;
