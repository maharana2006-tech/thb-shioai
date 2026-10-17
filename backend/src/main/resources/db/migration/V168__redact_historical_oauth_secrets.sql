-- BUG-161 R7: the live interceptor (CarrierApiLoggingInterceptor.redact)
-- replaces client_secret / refresh_token / access_token / password /
-- api_key / id_token / code_verifier before persisting, but the rows
-- written before that filter was wired (ids 1..1580, 8-12 Oct) still
-- carry those credentials in clear. V162 scrubbed client_id only.
--
-- Idempotent: regex_replace returns the input unchanged for rows that
-- already carry '***', so a re-run is a no-op after the first apply.
-- Any secret that was readable in the log must be treated as exposed —
-- the ops team is on the hook to rotate per the ADMIN runbook.
DO $$
BEGIN
    IF to_regclass('public.carrier_api_log') IS NULL THEN
        RAISE NOTICE 'V163 skipped — carrier_api_log missing';
        RETURN;
    END IF;

    -- JSON shape: "secret_key": "<value>"
    UPDATE carrier_api_log
       SET request_body = regexp_replace(
               request_body,
               '"(client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)"\s*:\s*"[^"]*"',
               '"\1":"***"', 'g'),
           response_body = regexp_replace(
               response_body,
               '"(client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)"\s*:\s*"[^"]*"',
               '"\1":"***"', 'g')
     WHERE request_body ~ '"(client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)"\s*:\s*"[^"]*"'
        OR response_body ~ '"(client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)"\s*:\s*"[^"]*"';

    -- Form shape: &client_secret=<value> or ?client_secret=<value>
    UPDATE carrier_api_log
       SET request_body = regexp_replace(
               request_body,
               '([?&])(client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)=[^&\s]*',
               '\1\2=***', 'g')
     WHERE request_body ~ '[?&](client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)=';

    -- Form shape at start of body: client_secret=<value>&... (no `?`/`&` prefix)
    UPDATE carrier_api_log
       SET request_body = regexp_replace(
               request_body,
               '^(client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)=[^&\s]*',
               '\1=***')
     WHERE request_body ~ '^(client_secret|clientSecret|refresh_token|refreshToken|access_token|accessToken|id_token|idToken|code_verifier|password|api_key|apiKey)=';

    -- SOAP shape (Stamps SWSIM): <Password>...</Password>
    UPDATE carrier_api_log
       SET request_body = regexp_replace(request_body,
               '(<(?:\w+:)?Password>)[^<]*', '\1***', 'gi'),
           response_body = regexp_replace(response_body,
               '(<(?:\w+:)?Password>)[^<]*', '\1***', 'gi')
     WHERE request_body ~* '<(\w+:)?Password>'
        OR response_body ~* '<(\w+:)?Password>';
END $$;
