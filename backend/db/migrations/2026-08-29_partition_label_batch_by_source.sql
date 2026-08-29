-- ============================================================================
-- Partition label_batch (orders) by order_source into separate physical tables
--   label_batch_manual  -> MANUAL
--   label_batch_bulk    -> BULK (CSV/Excel)
--   label_batch_api     -> API, WMS, ERP  (WMS/ERP fold into API)
--   label_batch_default -> anything else (safety net)
--
-- The logical table `label_batch` stays the same for all application code:
-- every query, join, and the shared order_no sequence keep working, while each
-- source now lives in its own physical table under the hood.
--
-- Postgres requires the partition key (order_source) to be part of the primary
-- key, so the PK becomes composite (order_no, order_source). order_no stays
-- globally unique via the shared sequence, so Hibernate's single-column @Id
-- (order_no) still identifies rows correctly.
--
-- Reversible: the original table is preserved as label_batch_old until verified.
-- To roll back: DROP TABLE label_batch CASCADE; ALTER TABLE label_batch_old
-- RENAME TO label_batch; (then recreate its indexes / FK).
-- ============================================================================

BEGIN;

-- 1. Backfill order_source so no row is NULL (partition key must be NOT NULL).
--    Mirrors the read-side COALESCE in OrderRepository.
UPDATE label_batch
   SET order_source = CASE WHEN is_manual = 'Y' THEN 'MANUAL' ELSE 'API' END
 WHERE order_source IS NULL OR btrim(order_source) = '';

-- 2. Drop the one FK that can't reference a partitioned table's composite PK.
--    (order_lines.order_no -> label_batch.order_no). The relationship is kept
--    in the app; order_no is globally unique.
ALTER TABLE order_lines DROP CONSTRAINT IF EXISTS fk8gipygskhri99fsmpol6p05v0;

-- 3. Preserve the current table + its indexes/constraints under _old names.
ALTER TABLE label_batch RENAME TO label_batch_old;
ALTER INDEX label_batch_pkey                 RENAME TO label_batch_old_pkey;
ALTER INDEX idx_label_batch_tenant_created   RENAME TO idx_label_batch_tenant_created_old;
ALTER INDEX idx_label_batch_created_date     RENAME TO idx_label_batch_created_date_old;
ALTER INDEX idx_label_batch_cust_no          RENAME TO idx_label_batch_cust_no_old;
ALTER INDEX idx_label_batch_shipvia_cd       RENAME TO idx_label_batch_shipvia_cd_old;
ALTER INDEX idx_label_batch_cust_no_trgm     RENAME TO idx_label_batch_cust_no_trgm_old;
ALTER INDEX idx_label_batch_shipto_city_trgm RENAME TO idx_label_batch_shipto_city_trgm_old;
ALTER INDEX uk_label_batch_wms_external_id   RENAME TO uk_label_batch_wms_external_id_old;

-- 4. Create the partitioned parent with the same columns (LIKE copies column
--    definitions, defaults, NOT NULL and generated expressions).
CREATE TABLE label_batch
    (LIKE label_batch_old INCLUDING DEFAULTS INCLUDING GENERATED)
    PARTITION BY LIST (order_source);

-- Partition key must be NOT NULL; add a safety default so any insert path that
-- forgets order_source lands in API rather than failing.
ALTER TABLE label_batch ALTER COLUMN order_source SET DEFAULT 'API';
ALTER TABLE label_batch ALTER COLUMN order_source SET NOT NULL;

-- Composite PK (must include the partition key).
ALTER TABLE label_batch ADD CONSTRAINT label_batch_pkey PRIMARY KEY (order_no, order_source);

-- 5. One physical table per source.
CREATE TABLE label_batch_manual  PARTITION OF label_batch FOR VALUES IN ('MANUAL');
CREATE TABLE label_batch_bulk    PARTITION OF label_batch FOR VALUES IN ('BULK');
CREATE TABLE label_batch_api     PARTITION OF label_batch FOR VALUES IN ('API', 'WMS', 'ERP');
CREATE TABLE label_batch_default PARTITION OF label_batch DEFAULT;

-- 6. Recreate indexes on the parent (cascade to every partition). Names match
--    IndexInitializer exactly so its CREATE INDEX CONCURRENTLY IF NOT EXISTS
--    calls skip them at startup (CONCURRENTLY is invalid on a partitioned table).
CREATE INDEX idx_label_batch_tenant_created   ON label_batch (UPPER(COALESCE(tenant_id, cust_no)), created_date DESC);
CREATE INDEX idx_label_batch_created_date     ON label_batch (created_date DESC);
CREATE INDEX idx_label_batch_cust_no          ON label_batch (cust_no);
CREATE INDEX idx_label_batch_shipvia_cd       ON label_batch (shipvia_cd);
CREATE INDEX idx_label_batch_shipto_city_trgm ON label_batch USING gin (LOWER(shipto_city) gin_trgm_ops);
CREATE INDEX idx_label_batch_cust_no_trgm     ON label_batch USING gin (LOWER(cust_no) gin_trgm_ops);

-- WMS external-id uniqueness only matters for WMS rows, which live in the API
-- partition; a partitioned unique index would need the partition key, so the
-- partial unique index goes on the API partition where those ids actually land.
CREATE UNIQUE INDEX uk_label_batch_api_wms_external_id
    ON label_batch_api (wms_external_id) WHERE wms_external_id IS NOT NULL;

-- 7. Copy the data — each row routes to its partition by order_source.
INSERT INTO label_batch SELECT * FROM label_batch_old;

COMMIT;
