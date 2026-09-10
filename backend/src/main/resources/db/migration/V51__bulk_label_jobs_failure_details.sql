-- Bulk MED — replace unstructured failure_message text blob with a
-- parallel JSON column carrying per-order structured failure details.
-- The FE has been rendering failure_message inside a <pre> block since
-- Sprint 37; operators struggle to spot "which order failed for what
-- reason" in a wall of "order N: {message}\n" lines, especially when
-- carrier error messages contain their own newlines.
--
-- Column shape (JSONB when supported, TEXT fallback for pre-9.4 hosts
-- — every currently-supported Postgres version speaks JSONB, but the
-- fresh-DB guard pattern this codebase uses (V40, V44) always checks
-- to_regclass before touching a table, so a repeat run is a no-op):
--
--   [
--     { "orderNo": 12345, "code": "LABEL_ALREADY_GENERATED",
--       "message": "already had a label (tracking 1Z...)",
--       "at": "2026-09-10T13:04:22Z" },
--     ...
--   ]
--
-- The legacy failure_message column stays populated in the same
-- writer path so existing FE code doesn't break; the FE upgrades
-- opportunistically when failure_details_json is present.
DO $$
BEGIN
    IF to_regclass('public.bulk_label_jobs') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM information_schema.columns
           WHERE  table_schema = 'public'
             AND  table_name   = 'bulk_label_jobs'
             AND  column_name  = 'failure_details_json'
       ) THEN
        ALTER TABLE bulk_label_jobs
            ADD COLUMN failure_details_json JSONB;
        COMMENT ON COLUMN bulk_label_jobs.failure_details_json IS
            'Bulk MED — structured per-order failures. Array of '
            '{ orderNo:int, code:string, message:string, at:iso8601 } '
            'objects. Legacy human-readable summary still lives in '
            'failure_message; this column is the machine-parseable '
            'source the FE renders as a proper error table.';
    END IF;
END $$;
