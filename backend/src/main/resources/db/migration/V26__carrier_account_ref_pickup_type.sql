-- FDX-H1 (FedEx-audit follow-up) — per-account default pickupType.
--
-- Adds a nullable pickup_type column to carrier_account_ref so operators can
-- set the shipment-time pickup indicator per account instead of the pre-fix
-- hardcode of USE_SCHEDULED_PICKUP on every FedEx label. Pre-fix, drop-off /
-- on-demand shippers had labels rejected by FedEx (no standing pickup
-- exists) or (worse) produced labels that never got physically collected.
--
-- Value semantics (FedEx pickupType enum — other carriers no-op):
--   REGULAR_PICKUP           — standard scheduled pickup
--   REQUEST_COURIER          — on-demand courier request
--   DROP_BOX                 — shipper drops at a FedEx drop box
--   BUSINESS_SERVICE_CENTER  — shipper drops at a FedEx business service center
--   STATION                  — shipper drops at a FedEx station
--   USE_SCHEDULED_PICKUP     — shipper has a standing daily pickup (DEFAULT)
--   CONTACT_FEDEX_TO_SCHEDULE — auto-applied by the connector for return
--                              labels, never set on this column
--
-- Semantics of NULL:
--   NULL → resolver falls back to the hardcoded default USE_SCHEDULED_PICKUP
--          which matches the pre-FDX-H1 behavior. So existing rows read
--          exactly as they did before.
--
-- Deliberately NO backfill: silently flipping every existing account to a
-- new value would misclassify shippers who genuinely have scheduled pickups
-- but also happen to drop at a station on some days. Operators set the
-- column explicitly per account via the CarrierConnections drawer.
--
-- Fresh-DB pattern: guarded by to_regclass so a fresh Postgres skips this
-- migration and Hibernate materialises the column from the @Entity on next
-- boot. See docs/flyway-fresh-db-guard-pattern.md.
--
-- No code path reads this column until FDX-H2 (resolver) lands, so this
-- migration is zero-risk on its own.
DO $$
BEGIN
    IF to_regclass('public.carrier_account_ref') IS NOT NULL THEN
        ALTER TABLE carrier_account_ref
            ADD COLUMN IF NOT EXISTS pickup_type VARCHAR(30);
    END IF;
END $$;
