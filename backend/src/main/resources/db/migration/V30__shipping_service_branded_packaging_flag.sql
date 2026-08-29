-- Sprint 52 PR X — per-service flag for whether carrier-branded packaging
-- is allowed. Distinguishes "admin hasn't linked any presets yet" (flag=
-- true, empty service_package pool → operator sees the config-incomplete
-- warning) from "this service intentionally accepts only YOUR_PACKAGING"
-- (flag=false, empty pool by design → operator sees a "CUSTOM only" badge).
--
-- Ground-family services fall in the latter bucket. Pre-fix, they were
-- indistinguishable from unconfigured Express services in the admin UI,
-- and the manual-shipment dropdown showed operators FEDEX_ENVELOPE etc.
-- as pickable options → FedEx PACKAGINGTYPE.VALIDATION.ERROR at label time.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: ADD COLUMN IF NOT
-- EXISTS + WHERE-clause guard on the UPDATE prevents re-flip on re-runs.
-- See docs/flyway-fresh-db-guard-pattern.md.
DO $$
BEGIN
    IF to_regclass('public.shipping_service') IS NULL THEN
        RAISE NOTICE 'V30 skipped — shipping_service missing (fresh DB before first sync)';
        RETURN;
    END IF;

    ALTER TABLE shipping_service
        ADD COLUMN IF NOT EXISTS branded_packaging_allowed BOOLEAN NOT NULL DEFAULT true;

    -- ─── Ground-family services default to CUSTOM-only ────────────────────
    -- Explicit service_code list (not a LIKE %GROUND% pattern) so we don't
    -- accidentally flip a service that has "GROUND" in the name but is
    -- actually a hybrid (e.g. FEDEX_INTERNATIONAL_GROUND does accept
    -- 10kg/25kg boxes on some accounts — see V29 seed). Admin can flip
    -- individual services via the new ShippingServicesPage toggle.
    --
    -- FedEx: Ground, Home Delivery. FEDEX_INTERNATIONAL_GROUND left OUT —
    -- V29 already links 10kg/25kg boxes to it so branded IS legitimately
    -- allowed there.
    UPDATE shipping_service SET branded_packaging_allowed = false
     WHERE carrier = 'FEDEX'
       AND service_code IN ('FEDEX_GROUND', 'GROUND_HOME_DELIVERY');

    -- UPS: 03 (Ground), 92 (Ground Saver / SurePost — CUSTOM only in
    -- practice). Deliberately NOT flipping 12 (3 Day Select) because
    -- UPS ships that on the Express fleet and it can carry branded packages.
    UPDATE shipping_service SET branded_packaging_allowed = false
     WHERE carrier = 'UPS'
       AND service_code IN ('03', '92');

    -- USPS / SWSIM: Ground Advantage variants. USPS_GROUND_ADVANTAGE is
    -- the current wire code; PARCEL_SELECT_GROUND is the older name some
    -- catalogs still use.
    UPDATE shipping_service SET branded_packaging_allowed = false
     WHERE carrier = 'USPS'
       AND service_code IN ('USPS_GROUND_ADVANTAGE', 'GROUND_ADVANTAGE',
                            'PARCEL_SELECT_GROUND', 'PARCEL_SELECT');

    -- DHL Express has no true "ground" — no seed here. Admin can flip
    -- specific DHL services if their contract requires CUSTOM-only.

    RAISE NOTICE 'V30 seeded branded_packaging_allowed=false for ground-family services';
END $$;
