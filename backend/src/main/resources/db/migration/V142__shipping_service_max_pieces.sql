-- Service-level pieces-per-label cap, symmetric with carrier_package_catalog.max_pieces
-- added in V141. Rare in practice (packaging drives most single-piece
-- rules) but defensible — some return services + LTL add-ons cap at 1
-- regardless of the packaging. Null = no limit; 1 = single-piece only.
--
-- No seed values today — populate per-service via an UPDATE as business
-- rules surface during QA / ops. The FE picker reads this and gates
-- the "Add another box" buttons the same way it does for packaging.
--
-- Idempotent (see docs/flyway-fresh-db-guard-pattern.md).
DO $$
BEGIN
    IF to_regclass('public.shipping_service') IS NOT NULL THEN
        ALTER TABLE shipping_service
            ADD COLUMN IF NOT EXISTS max_pieces INTEGER;
    END IF;
END $$;
