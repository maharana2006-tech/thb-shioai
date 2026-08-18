-- Audit R2 #351 + #377 — @Version optimistic locking on two admin
-- surfaces that were silently losing edits under concurrent writes:
--   * routing_rule            (#351)
--   * carrier_shipping_limit  (#377)
--
-- Pre-fix, two admins editing the same row simultaneously → last save
-- wins, other's edits vanish with no signal. Now Hibernate stamps
-- version=0 on insert, bumps on every update, and throws
-- OptimisticLockingFailureException when a stale save arrives. Callers
-- translate to 409 CONFLICT with a dedicated error code so the FE can
-- prompt "someone else changed this — refresh and retry".
--
-- Column defaults to 0 on backfill so existing rows enter the version
-- lineage cleanly on next update.
--
-- Follows the fresh-DB Flyway pattern (docs/flyway-fresh-db-guard-pattern.md):
-- guarded by to_regclass so a fresh Postgres skips cleanly and
-- Hibernate materialises the column on the next boot with the
-- @Version annotation controlling default value semantics.
DO $$
BEGIN
    IF to_regclass('public.routing_rule') IS NOT NULL THEN
        ALTER TABLE routing_rule
            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
    END IF;
    IF to_regclass('public.carrier_shipping_limit') IS NOT NULL THEN
        ALTER TABLE carrier_shipping_limit
            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
    END IF;
END $$;
