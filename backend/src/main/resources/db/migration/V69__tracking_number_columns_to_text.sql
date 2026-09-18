-- Widen tracking_number columns from VARCHAR(255) to TEXT.
-- When importing orders or generating labels, tracking numbers from some carriers
-- (especially USPS with routing codes or concatenated identifiers) can exceed 255 characters,
-- causing "value too long for type character varying(255)" errors on insert.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: no-op when columns are already TEXT.

DO $$
BEGIN
    IF to_regclass('public.order_label_tracking') IS NOT NULL AND EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'order_label_tracking'
           AND column_name = 'tracking_number'
           AND character_maximum_length = 255
    ) THEN
        ALTER TABLE order_label_tracking ALTER COLUMN tracking_number TYPE TEXT;
        RAISE NOTICE 'V69 widened order_label_tracking.tracking_number to TEXT';
    END IF;

    IF to_regclass('public.label_package') IS NOT NULL AND EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'label_package'
           AND column_name = 'tracking_number'
           AND character_maximum_length = 255
    ) THEN
        ALTER TABLE label_package ALTER COLUMN tracking_number TYPE TEXT;
        RAISE NOTICE 'V69 widened label_package.tracking_number to TEXT';
    END IF;
END $$;
