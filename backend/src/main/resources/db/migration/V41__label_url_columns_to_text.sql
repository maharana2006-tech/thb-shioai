-- Widen label_url columns from VARCHAR(500) to TEXT. Pre-fix, PR #586
-- corrected the UPS ShipmentResults parser to actually persist the
-- base64-encoded GraphicImage bytes (previously silently dropped due
-- to a SOAP-to-JSON single-package quirk). Those bytes are typically
-- several KB (a raw ZPL 4×6" label is ~2-5 KB before base64; GIFs +
-- PDFs are much larger). VARCHAR(500) is orders of magnitude too
-- small; multi-package inserts on shipment_batch failed with
-- "value too long for type character varying(500)" and 5xx'd the
-- Generate Label call.
--
-- Sibling label columns are already TEXT (see label_package,
-- order_label_tracking, shipment.label_pdf, shipment_batch.
-- master_label_pdf); this migration just closes the parity gap on
-- the two _url variants.
--
-- Kept master_tracking_url at VARCHAR(500) since it is genuinely a
-- URL string (order tracking public link), not label bytes.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: no-op when
-- columns are already TEXT.
DO $$
BEGIN
    IF to_regclass('public.shipment_batch') IS NOT NULL AND EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'shipment_batch'
           AND column_name = 'master_label_url'
           AND character_maximum_length = 500
    ) THEN
        ALTER TABLE shipment_batch ALTER COLUMN master_label_url TYPE TEXT;
        RAISE NOTICE 'V41 widened shipment_batch.master_label_url to TEXT';
    END IF;

    IF to_regclass('public.shipment') IS NOT NULL AND EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'shipment'
           AND column_name = 'label_url'
           AND character_maximum_length = 500
    ) THEN
        ALTER TABLE shipment ALTER COLUMN label_url TYPE TEXT;
        RAISE NOTICE 'V41 widened shipment.label_url to TEXT';
    END IF;
END $$;
