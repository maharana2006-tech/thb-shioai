-- Bulk Mailer lists batches page by page, filtered in the database. A
-- client-scoped user may only see their own client's batches, and the owner
-- lived only inside each batch's rows (rows_json, or import_batch_row for
-- WMS/API fetches) — so every list call parsed every batch. The owner is now
-- a column, written whenever a batch's rows are.

ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS client_code VARCHAR(50);

-- WMS / API batches: the first row with a client code.
UPDATE import_batch b
   SET client_code = upper(trim(r.client_code))
  FROM (SELECT DISTINCT ON (import_batch_id) import_batch_id, client_code
          FROM import_batch_row
         WHERE client_code IS NOT NULL AND trim(client_code) <> ''
         ORDER BY import_batch_id, row_number) r
 WHERE b.client_code IS NULL AND r.import_batch_id = b.id;

-- File batches: the first element of rows_json with a clientCode. Malformed
-- payloads are skipped rather than failing the migration.
UPDATE import_batch b
   SET client_code = upper(trim(x.cc))
  FROM (SELECT id,
               (SELECT e ->> 'clientCode'
                  FROM jsonb_array_elements(rows_json::jsonb) e
                 WHERE coalesce(trim(e ->> 'clientCode'), '') <> ''
                 LIMIT 1) AS cc
          FROM import_batch
         WHERE client_code IS NULL
           AND rows_json IS NOT NULL
           AND pg_input_is_valid(rows_json, 'jsonb')
           AND jsonb_typeof(rows_json::jsonb) = 'array') x
 WHERE b.id = x.id AND x.cc IS NOT NULL;

-- The list query: live / Trash, by source, newest first.
CREATE INDEX IF NOT EXISTS idx_import_batch_list ON import_batch (deleted_at, source, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_import_batch_client ON import_batch (client_code);
