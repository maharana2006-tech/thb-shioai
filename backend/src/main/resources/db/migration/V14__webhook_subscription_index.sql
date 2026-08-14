-- Sprint 51 BP-M1 — composite index for the api-key-scoped subscription
-- lookup added to ExternalWebhookSubscriptionRepository.
--
-- Pre-BP-M1 the dispatcher's fire() path called
-- findByEventAndActiveTrue(event) — a scan on
-- external_webhook_subscription whose cost grows with total subscription
-- count across every tenant. On the hot label-generation path this ran
-- once per label per tenant; the Java-side filter then discarded all-but-
-- caller's rows. Post-BP-M1 fire() calls findByEventAndApiKeyIdAndActiveTrue
-- when the caller is known — an index-only seek against this composite.
--
-- The existing idx_ext_webhook_key_event (api_key_id, event) covers the
-- reverse (list-my-webhooks) query but doesn't help the event-primary
-- dispatcher path because Postgres prefers a leading equality on the
-- most-selective column and event is more selective than api_key_id
-- (4 enum values vs N tenants).
--
-- Follows Sprint 50 PR I fresh-DB pattern: to_regclass guard so a fresh
-- Postgres with no table yet skips the CREATE INDEX cleanly (Hibernate
-- creates the table + this index re-runs on the next boot).
DO $$
BEGIN
    IF to_regclass('external_webhook_subscription') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_ext_webhook_event_active_key
            ON external_webhook_subscription (event, active, api_key_id);
    END IF;
END $$;
