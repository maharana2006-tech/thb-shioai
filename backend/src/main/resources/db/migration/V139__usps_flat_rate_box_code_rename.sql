-- Rename USPS flat-rate box codes from V32's SM_/MD_/LG_ short-form to the
-- canonical SMALL_/MEDIUM_/LARGE_ long-form that StampsConnector.mapPackagingTypeToSera
-- (and SERA itself) expects. Pre-fix, QA run #2 saw every USPS flat-rate-box
-- submit fall to the mapper's default pass-through and ship 'sm_flat_rate_box'
-- to SERA, which 899999'd with "packaging_type invalid" (9 failures across
-- Ground Advantage / Priority / Priority Express × Small/Medium/Large).
--
-- Catalogue table drives the operator-facing picker; package_preset is what
-- the FE actually sends on packages[i].packageType. Both need the rename so
-- newly-synced presets AND already-synced ones ship the correct shape.
--
-- Idempotent (see docs/flyway-fresh-db-guard-pattern.md).
DO $$
BEGIN
    IF to_regclass('public.carrier_package_catalog') IS NOT NULL THEN
        UPDATE carrier_package_catalog
           SET code = 'SMALL_FLAT_RATE_BOX'
         WHERE carrier_code = 'USPS' AND code = 'SM_FLAT_RATE_BOX';
        UPDATE carrier_package_catalog
           SET code = 'MEDIUM_FLAT_RATE_BOX'
         WHERE carrier_code = 'USPS' AND code = 'MD_FLAT_RATE_BOX';
        UPDATE carrier_package_catalog
           SET code = 'LARGE_FLAT_RATE_BOX'
         WHERE carrier_code = 'USPS' AND code = 'LG_FLAT_RATE_BOX';
    END IF;

    IF to_regclass('public.package_preset') IS NOT NULL THEN
        UPDATE package_preset
           SET carrier_package_code = 'SMALL_FLAT_RATE_BOX'
         WHERE carrier = 'USPS' AND carrier_package_code = 'SM_FLAT_RATE_BOX';
        UPDATE package_preset
           SET carrier_package_code = 'MEDIUM_FLAT_RATE_BOX'
         WHERE carrier = 'USPS' AND carrier_package_code = 'MD_FLAT_RATE_BOX';
        UPDATE package_preset
           SET carrier_package_code = 'LARGE_FLAT_RATE_BOX'
         WHERE carrier = 'USPS' AND carrier_package_code = 'LG_FLAT_RATE_BOX';
    END IF;
END $$;
