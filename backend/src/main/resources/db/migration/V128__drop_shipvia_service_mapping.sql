-- V128 — Code-Maps/SSM merge, final step: drop the old SSM tables.
--
-- Everything the old /settings/shipping-service-mapping page wrote now
-- lives on client_shipvia_code_map (+ its warehouse_id column and the
-- client_shipvia_code_map_package sidecar). V126 added those columns;
-- V127 added the sidecar; all callers were re-pointed in the same PR.
--
-- Must drop in child-first order (sidecars before parent). CASCADE on the
-- parent is belt-and-braces for any lingering FKs we didn't inventory —
-- app is pre-prod with test data only.
--
-- Guards: to_regclass makes this a no-op on fresh DBs created after V125.

DO $$
BEGIN
    IF to_regclass('public.ship_method_rule_package') IS NOT NULL THEN
        DROP TABLE public.ship_method_rule_package;
    END IF;

    IF to_regclass('public.ship_method_rule_warehouse') IS NOT NULL THEN
        DROP TABLE public.ship_method_rule_warehouse;
    END IF;

    IF to_regclass('public.shipvia_service_mapping') IS NOT NULL THEN
        DROP TABLE public.shipvia_service_mapping CASCADE;
    END IF;
END $$;
