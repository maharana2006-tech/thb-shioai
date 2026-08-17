-- Audit L/B6 — the Sprint 52 addition of `direction` (V15) never updated
-- the `(carrier_code, service_code, scope, effective_from)` unique key
-- to include the new discriminator. The V15 seed itself worked around
-- the miss by using a slightly later effective_from timestamp for the
-- new UPS FORWARD row (see V15 line 63 comment) — a workaround, not a
-- design.
--
-- Symptom: admin creating both a FORWARD and RETURN row for the same
-- carrier/service/scope with the same effective_from would collide on
-- the DB constraint, even though the two rows are semantically distinct.
--
-- Fix: recreate the constraint to include `direction`. Postgres treats
-- NULLs as distinct in unique constraints, so pre-Sprint 52 rows with
-- NULL direction stay valid (each NULL is its own value); and multiple
-- (carrier, service, scope, effective_from) rows with different non-null
-- directions can now coexist without gaming the timestamp.
--
-- Follows the fresh-DB Flyway pattern (docs/flyway-fresh-db-guard-pattern.md):
-- guarded by to_regclass so a fresh Postgres with no table yet skips
-- cleanly and lets Hibernate materialise the table + constraint on the
-- next boot with the corrected @UniqueConstraint spec.
DO $$
BEGIN
    IF to_regclass('public.carrier_shipping_limit') IS NOT NULL THEN
        -- Only drop-and-recreate if the OLD constraint (no direction) still
        -- exists — Hibernate on a fresh boot already lays down the corrected
        -- shape, so re-running this migration in that state is a no-op.
        IF EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_name = 'carrier_shipping_limit'
               AND constraint_name = 'uk_carrier_limit_key'
        ) THEN
            ALTER TABLE carrier_shipping_limit DROP CONSTRAINT uk_carrier_limit_key;
        END IF;

        -- Idempotent add — the new constraint name matches the entity
        -- annotation so Hibernate's schema validator (if enabled) sees
        -- the expected key on next boot.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_name = 'carrier_shipping_limit'
               AND constraint_name = 'uk_carrier_limit_key'
        ) THEN
            ALTER TABLE carrier_shipping_limit
                ADD CONSTRAINT uk_carrier_limit_key
                UNIQUE (carrier_code, service_code, scope, direction, effective_from);
        END IF;
    END IF;
END $$;
