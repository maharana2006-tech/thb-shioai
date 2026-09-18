-- Widen label_package.description from VARCHAR(500) to TEXT to support longer descriptions.
-- When generating labels from Order History, order line descriptions can exceed 500 characters
-- (e.g. concatenated or detailed product descriptions), causing "value too long for type
-- character varying(500)" errors on label_package insert.
--
-- Similar to V41 which widened label_url columns; description is free-text like label_file_path
-- and should be TEXT. Fresh-DB safe: guarded by to_regclass. Idempotent: no-op when
-- column is already TEXT.

DO $$
BEGIN
    IF to_regclass('public.label_package') IS NOT NULL AND EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'label_package'
           AND column_name = 'description'
           AND character_maximum_length = 500
    ) THEN
        ALTER TABLE label_package ALTER COLUMN description TYPE TEXT;
        RAISE NOTICE 'V68 widened label_package.description to TEXT';
    END IF;
END $$;
