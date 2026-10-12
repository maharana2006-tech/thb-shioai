-- Drop invalid USPS service↔packaging compatibility rows. QA R9 proved
-- SERA rejects these combos as "Invalid rate. The mail class X cannot be
-- used with package type Y for domestic mail." (error 5177601) — they
-- never produced a label, just a save-as-error order. The picker reads
-- service_package (NewShipmentPage.compatiblePresetIdsForService) so
-- deleting these rows hides the combos in the UI AND stops the backend
-- pre-flight from fanning out doomed /rates calls.
--
-- USPS actual rules:
--   · Flat Rate Envelope: Priority Mail + Priority Mail Express only (NOT Ground Advantage)
--   · Small / Medium / Large Flat Rate Box: Priority Mail only (NOT GA, NOT Express)
--
-- Idempotent (see docs/flyway-fresh-db-guard-pattern.md).
DO $$
BEGIN
    IF to_regclass('public.service_package') IS NULL
       OR to_regclass('public.shipping_service') IS NULL
       OR to_regclass('public.package_preset') IS NULL THEN
        RAISE NOTICE 'V140 skipped — tables missing (fresh DB before first boot)';
        RETURN;
    END IF;

    DELETE FROM service_package sp
     USING shipping_service s, package_preset p
     WHERE sp.service_id = s.id
       AND sp.preset_id  = p.id
       AND s.carrier = 'USPS'
       AND (
           -- Ground Advantage never ships with any USPS flat-rate code.
           (s.service_code = 'GROUND_ADVANTAGE'
            AND p.carrier_package_code IN (
                'FLAT_RATE_ENVELOPE', 'SMALL_FLAT_RATE_BOX',
                'MEDIUM_FLAT_RATE_BOX', 'LARGE_FLAT_RATE_BOX'))
        OR
           -- Priority Mail Express cannot ship with flat-rate BOXES.
           -- Flat Rate Envelope + Express is still valid (confirmed R9 ✓).
           (s.service_code = 'PRIORITY_EXPRESS'
            AND p.carrier_package_code IN (
                'SMALL_FLAT_RATE_BOX', 'MEDIUM_FLAT_RATE_BOX',
                'LARGE_FLAT_RATE_BOX'))
       );
END $$;
