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
        -- Backfill NULL slugs with a random 16-byte base64url (dropped
        -- padding + swapped +/ for -_ so it's URL-safe). pgcrypto's
        -- gen_random_bytes is available on every supported Postgres.
        UPDATE import_batch
           SET slug = translate(
                          rtrim(encode(gen_random_bytes(16), 'base64'), '='),
                          '+/', '-_')
         WHERE slug IS NULL;

        ALTER TABLE import_batch ALTER COLUMN slug SET NOT NULL;

        CREATE UNIQUE INDEX IF NOT EXISTS ux_import_batch_slug
            ON import_batch (slug);
    END IF;
END $$;
