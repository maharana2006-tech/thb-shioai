-- Persist carrier-side label identifiers so operations that need them
-- (SERA void by label_id, SERA reprint by label_id) can look up the
-- reference by tracking number.
--
-- Prompted by Stamps.com / Endicia SERA REST API: void + reprint key
-- off the {@code label_id} UUID SERA returns from POST /sera/v1/labels,
-- NOT the tracking number the way SWSIM's CancelIndicium does. Every
-- other carrier (FedEx, UPS, DHL, SWSIM) voids by tracking so the
-- field stays null for those flows — additive-only, no behaviour
-- change for legacy accounts.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: ADD COLUMN IF
-- NOT EXISTS.
DO $$
BEGIN
    IF to_regclass('public.label_package') IS NULL THEN
        RAISE NOTICE 'V44 skipped — label_package table missing (fresh DB before first sync)';
        RETURN;
    END IF;
    ALTER TABLE label_package
        ADD COLUMN IF NOT EXISTS carrier_label_ref VARCHAR(128);
    RAISE NOTICE 'V44 added label_package.carrier_label_ref (SERA label_id / carrier-specific label ref)';
END $$;
