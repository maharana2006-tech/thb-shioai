-- Label batch numbers come from a sequence.
--
-- They used to be MAX(label_batch.batch_id) + 1, taken when a file is uploaded.
-- A batch only reaches label_batch once its labels are generated, so two files
-- uploaded before either generated were both given the same number (imports
-- #83 and #84 both showed "Batch 58").
--
-- Start above every number already handed out: generated batches, imports, and
-- numbers stamped on rows still waiting in Import history or staging.
CREATE SEQUENCE IF NOT EXISTS label_batch_number_seq;

DO $$
DECLARE
    m bigint := 0;
    v bigint;
BEGIN
    SELECT COALESCE(MAX(batch_id), 0) INTO v FROM label_batch;
    m := GREATEST(m, v);
    SELECT COALESCE(MAX(label_batch_id), 0) INTO v FROM import_batch;
    m := GREATEST(m, v);

    BEGIN
        SELECT COALESCE(MAX((x->>'batchId')::bigint), 0) INTO v
          FROM import_batch b,
               jsonb_array_elements(CASE WHEN jsonb_typeof(b.rows_json::jsonb) = 'array'
                                         THEN b.rows_json::jsonb ELSE '[]'::jsonb END) x
         WHERE x->>'batchId' ~ '^[0-9]+$';
        m := GREATEST(m, v);
    EXCEPTION WHEN others THEN
        RAISE NOTICE 'import_batch rows_json scan skipped: %', SQLERRM;
    END;

    BEGIN
        SELECT COALESCE(MAX((r.row_json::jsonb->>'batchId')::bigint), 0) INTO v
          FROM import_staging_row r
         WHERE r.row_json::jsonb->>'batchId' ~ '^[0-9]+$';
        m := GREATEST(m, v);
    EXCEPTION WHEN others THEN
        RAISE NOTICE 'import_staging_row row_json scan skipped: %', SQLERRM;
    END;

    PERFORM setval('label_batch_number_seq', m + 1, false);
END $$;
