-- Sprint 50 PR N post-audit indexes.
--
-- The post-Sprint-50 deep audit surfaced several hot query paths that
-- were doing full table scans because their filter columns weren't
-- indexed. All of these are pure additions — no data changes, no
-- constraint tightening (FKs + NOT NULL are deferred as separate
-- higher-risk work).
--
-- Follows the Sprint 50 PR I fresh-DB pattern: every reference to a
-- table Hibernate creates via entity metadata is guarded with a
-- `to_regclass(...)` DO block so the migration is a no-op on a truly
-- fresh Postgres (Hibernate creates the tables later and Flyway just
-- records the version).

-- ---------- Finding #1: label_batch tenant filter is scan-heavy ----------
-- Every hot query in OrderRepository filters on
-- `UPPER(COALESCE(tenant_id, cust_no))` — dashboards, city dist, avg
-- weight, queue stats, per-tenant listing, cascade guard. With no
-- functional index on this expression, each query full-scans
-- label_batch (the largest table).
--
-- A functional index over the exact expression the queries use lets
-- Postgres seek directly. Column-only indexes on tenant_id / cust_no
-- would NOT get picked up by the planner for the UPPER(COALESCE(...))
-- expression, so the composite functional index is what we need.
DO $$
BEGIN
    IF to_regclass('label_batch') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_label_batch_tenant_scope
            ON label_batch (UPPER(COALESCE(tenant_id, cust_no)));
    END IF;
END $$;

-- ---------- Finding #3: api_key.rotated_from_id ----------
-- Sprint 50 Tier 0.5 PR C added rotated_from_id but no index. The grace
-- period revoke sweep (`WHERE rotated_from_id = ?`) full-scans api_key.
-- Small table today but grows unbounded over time as rotation history
-- accumulates.
DO $$
BEGIN
    IF to_regclass('api_key') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_api_key_rotated_from
            ON api_key (rotated_from_id)
            WHERE rotated_from_id IS NOT NULL;
    END IF;
END $$;

-- ---------- Finding #5: user_invites.expires_at ----------
-- The "expire stale invites" sweeper filters `WHERE expires_at < now()`
-- and full-scans user_invites. Nominal impact today (small volume) but
-- the sweeper runs on a schedule and scan cost grows with signup
-- volume. Partial index (only unconsumed rows) keeps size minimal —
-- consumed invites don't need expiry lookups.
DO $$
BEGIN
    IF to_regclass('user_invites') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_user_invites_expires_unconsumed
            ON user_invites (expires_at)
            WHERE consumed_at IS NULL;
    END IF;
END $$;

-- ---------- NOTE — Foreign keys deferred ----------
-- Findings #2 (api_key.client_code → clients), #4 (user_invites.client_code
-- → clients), and #19 (api_key.client_code NOT NULL) require data
-- validation on prod first. Any existing orphan rows would fail the
-- constraint addition and block the migration. Ship after ops confirms
-- zero orphans via:
--
--   SELECT client_code FROM api_key WHERE client_code IS NOT NULL
--    AND client_code NOT IN (SELECT client_code FROM clients);
--   SELECT client_code FROM user_invites WHERE client_code IS NOT NULL
--    AND client_code NOT IN (SELECT client_code FROM clients);
--   SELECT id FROM api_key WHERE client_code IS NULL;
