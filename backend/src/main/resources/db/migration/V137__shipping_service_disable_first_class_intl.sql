-- Disable the retired USPS First-Class Package International Service.
--
-- REGULATORY_REFERENCE. USPS retired First-Class Package International
-- Service (FCPIS) on 2024-01-21. Postal Bulletin 22644 (2023-12-14),
-- "Changes to Prices and Mailing Standards for Market Dominant and
-- Competitive Products." Auctane's SERA v1 enum dropped
-- usps_first_class_package_international_service in sync; the service
-- now 400s with error_code 899999 "service_type specified is invalid."
--
-- Keeping the row (vs DELETE) preserves historical FK integrity:
-- order_label_tracking + label_batch rows for shipments previously
-- generated under this service_code stay queryable + reprintable
-- (void path works by carrier_label_ref, not service_type). Flipping
-- enabled=false hides it from the /orders/new service picker + the
-- rate-shop callers, which is the operator-facing side.
--
-- Fresh-DB-guard pattern: wrapped in a to_regclass check so cold
-- bootstraps that reach V137 before shipping_service exists silently
-- no-op (see docs/flyway-fresh-db-guard-pattern.md). The UPDATE itself
-- is idempotent — rerunning sets enabled=false on an already-false
-- row with no observable change.

DO $$
BEGIN
    IF to_regclass('public.shipping_service') IS NOT NULL THEN
        UPDATE shipping_service
           SET enabled = FALSE,
               updated_at = NOW()
         WHERE carrier = 'USPS'
           AND service_code = 'FIRST_CLASS_INTL'
           AND enabled = TRUE;

        RAISE NOTICE 'V137 disabled FIRST_CLASS_INTL (USPS retired FCPIS 2024-01-21 — Postal Bulletin 22644)';
    END IF;
END $$;
