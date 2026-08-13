-- Sprint 51 M-Perf (audit finding BP-M1) — composite index on
-- external_webhook_subscription for the ExternalWebhookDispatcher.fire
-- hot path.
--
-- Pre-M-Perf the dispatcher called findByEventAndActiveTrue and
-- Java-side-filtered by apiKeyId. As the subscription table grows
-- (10s of partners × 4 event types × active flag) each fire allocates
-- the full list even when only one row matches.
--
-- M-Perf adds findByEventAndApiKeyIdAndActiveTrue on the repository.
-- This migration lets Postgres seek via a composite index instead of
-- scanning every active row. The (event, api_key_id, active) column
-- order matches the query's WHERE clause literally so the planner
-- can use an index-only scan for the common case.
--
-- Follows the Sprint 50 PR I fresh-DB pattern: to_regclass guard so
-- Hibernate creates the table on a truly fresh Postgres and Flyway
-- skips the index create until the table exists.

DO $$
BEGIN
    IF to_regclass('external_webhook_subscription') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_webhook_sub_event_apikey_active
            ON external_webhook_subscription (event, api_key_id, active);
    ELSE
        RAISE NOTICE 'V10: external_webhook_subscription does not exist yet — Hibernate will create; index recreated on next Flyway run (fresh-DB path).';
    END IF;
END
$$;
