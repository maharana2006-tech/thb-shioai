-- Bulk Mailer shows each batch's labels at a glance ("18 generated · 2
-- pending · 1 voided") without reading its rows. Written whenever a batch's
-- rows are; label_orders maps each generated order to its row count, so a
-- void made anywhere (read live from the order) can be counted in rows.
ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS labels_generated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS labels_failed    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS label_orders     TEXT;
-- False until the counts come from the batch's rows (a batch whose stored rows
-- are gone has no counts to show, rather than "all pending").
ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS labels_counted   BOOLEAN NOT NULL DEFAULT FALSE;

-- WMS / API batches: rows in import_batch_row.
UPDATE import_batch b SET
    labels_generated = s.gen, labels_failed = s.failed, label_orders = s.orders, labels_counted = TRUE
  FROM (SELECT import_batch_id,
               count(*) FILTER (WHERE upper(generated_status) IN ('GENERATED', 'QUEUED_USPS')) AS gen,
               count(*) FILTER (WHERE upper(generated_status) = 'FAILED') AS failed,
               (SELECT jsonb_object_agg(o.no, o.n)::text
                  FROM (SELECT generated_order_no AS no, count(*) AS n
                          FROM import_batch_row r2
                         WHERE r2.import_batch_id = r.import_batch_id
                           AND upper(r2.generated_status) IN ('GENERATED', 'QUEUED_USPS')
                           AND r2.generated_order_no ~ '^[0-9]+$'
                         GROUP BY generated_order_no) o) AS orders
          FROM import_batch_row r
         GROUP BY import_batch_id) s
 WHERE b.id = s.import_batch_id;

-- File batches: rows in rows_json (malformed payloads skipped).
UPDATE import_batch b SET
    labels_generated = s.gen, labels_failed = s.failed, label_orders = s.orders, labels_counted = TRUE
  FROM (SELECT id,
               (SELECT count(*) FROM jsonb_array_elements(rows_json::jsonb) e
                 WHERE upper(e ->> 'generatedStatus') IN ('GENERATED', 'QUEUED_USPS')) AS gen,
               (SELECT count(*) FROM jsonb_array_elements(rows_json::jsonb) e
                 WHERE upper(e ->> 'generatedStatus') = 'FAILED') AS failed,
               (SELECT jsonb_object_agg(o.no, o.n)::text
                  FROM (SELECT e ->> 'generatedOrderNo' AS no, count(*) AS n
                          FROM jsonb_array_elements(rows_json::jsonb) e
                         WHERE upper(e ->> 'generatedStatus') IN ('GENERATED', 'QUEUED_USPS')
                           AND (e ->> 'generatedOrderNo') ~ '^[0-9]+$'
                         GROUP BY e ->> 'generatedOrderNo') o) AS orders
          FROM import_batch
         WHERE rows_json IS NOT NULL
           AND pg_input_is_valid(rows_json, 'jsonb')
           AND jsonb_typeof(rows_json::jsonb) = 'array'
           AND coalesce(upper(source), 'BULK') NOT IN ('WMS', 'API')) s
 WHERE b.id = s.id;
