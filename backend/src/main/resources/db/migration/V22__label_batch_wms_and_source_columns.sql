-- Prod-deploy blocker fix — two @Column fields on the Order entity landed
-- via direct-push without matching Flyway migrations, so a prod boot
-- (application-prod.properties: spring.jpa.hibernate.ddl-auto=validate)
-- would refuse to start with "missing column" errors:
--
--   * label_batch.order_source        (added Jul 24 2026 by 9062877 — WMS/API/ERP/MANUAL provenance)
--   * label_batch.wms_external_id     (added Aug 20 2026 by ce87eed — WMS pull idempotency key)
--
-- Local dev runs ddl-auto=update so Hibernate silently materialised both
-- columns on first boot and hid the gap; prod caught it.
--
-- Also adds an index on wms_external_id — every WmsService.pullShippable
-- call runs existsByWmsExternalId(externalId) once per shippable row for
-- the idempotent-re-pull guard, so a pull of N rows without the index is
-- N sequential scans of label_batch. UNIQUE by design: two orders sharing
-- the same WMS externalId is a real-world data bug (WMS should stamp
-- unique ids), and a soft duplicate would break the "skip if already
-- imported" guarantee. UNIQUE + WHERE IS NOT NULL keeps legacy MANUAL /
-- API rows (which have wms_external_id = NULL) out of the constraint.
--
-- Follows the fresh-DB Flyway pattern (docs/flyway-fresh-db-guard-pattern.md):
-- guarded by to_regclass so a fresh Postgres skips cleanly and Hibernate
-- materialises the columns on the next boot.
DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NOT NULL THEN
        ALTER TABLE label_batch
            ADD COLUMN IF NOT EXISTS order_source     VARCHAR(20),
            ADD COLUMN IF NOT EXISTS wms_external_id  VARCHAR(100);

        CREATE UNIQUE INDEX IF NOT EXISTS uk_label_batch_wms_external_id
            ON label_batch (wms_external_id)
            WHERE wms_external_id IS NOT NULL;
    END IF;
END $$;
