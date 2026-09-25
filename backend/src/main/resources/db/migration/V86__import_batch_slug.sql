-- Opaque slug for import_batch — replaces the numeric id in URLs +
-- API path parameters so a scoped USER cannot enumerate batch IDs
-- across the tenancy (previously GET /orders/import/history/{id}
-- returned 403 for real batches and 404 for empty ids — a response
-- oracle that leaked existence + growth-rate signal).
--
-- The FE now navigates to /bulk/batches/<slug> and calls
-- /api/v1/orders/import/history/<slug>; the backend resolves slug →
-- internal id via ImportBatchRepository.findBySlug.
--
-- 22-char base64url is derived from 16 random bytes (128 bits =
-- unguessable). Populated for existing rows by the DO block below;
-- new rows get one via ImportBatch.@PrePersist.
ALTER TABLE IF EXISTS import_batch ADD COLUMN IF NOT EXISTS slug VARCHAR(22);

DO $$
BEGIN
    IF to_regclass('import_batch') IS NOT NULL THEN
        -- Backfill NULL slugs. gen_random_uuid() is core Postgres from
        -- 13 onwards (no pgcrypto extension needed); two concat'd UUIDs
        -- give 64 hex chars → substring 22 → 88 bits of entropy per
        -- pre-existing row. Java's @PrePersist keeps generating 128-bit
        -- base64url slugs for all new rows, so backfill-only entropy is
        -- amply unguessable.
        UPDATE import_batch
           SET slug = substring(
                          replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '')
                          FROM 1 FOR 22)
         WHERE slug IS NULL;

        ALTER TABLE import_batch ALTER COLUMN slug SET NOT NULL;

        CREATE UNIQUE INDEX IF NOT EXISTS ux_import_batch_slug
            ON import_batch (slug);
    END IF;
END $$;
