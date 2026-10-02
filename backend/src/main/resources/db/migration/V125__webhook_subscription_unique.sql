-- Audit W5 (#334) — one subscription per (api_key_id, event, url). Pre-fix
-- the FE's "New subscription" button could create two rows with the same
-- triple; the dispatcher then fired twice for every event, which looks
-- (and bills) like a bug to the partner on the receiving end.
--
-- DB guard rather than app-layer dedupe: it survives concurrent saves
-- (two admins clicking at once) and the controller just has to translate
-- the resulting constraint-violation into a 409.
--
-- url is TEXT (no length), so the index key is capped at the Postgres
-- btree 2704-byte limit — in practice webhook URLs stay well under that;
-- the few that approach the limit (query-string-heavy signed callbacks)
-- aren't representative of the deduplication intent anyway. If this ever
-- fires at CREATE INDEX time we'll move to a hash of the URL.

DO $$
BEGIN
    IF to_regclass('external_webhook_subscription') IS NOT NULL THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_ext_webhook_key_event_url
            ON external_webhook_subscription (api_key_id, event, url);
    END IF;
END $$;
