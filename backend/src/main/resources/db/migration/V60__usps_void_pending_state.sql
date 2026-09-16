-- V60 — USPS_DIRECT integration: void-reconciliation state on order_label_tracking.
--
-- PR-D wires an OPTIMISTIC void UX for USPS_DIRECT because USPS APIs v3
-- do not expose a synchronous void endpoint (see
-- docs/usps-direct-integration.md §12 locked decision #19). The flow:
--
--   1. Operator clicks void on an order → CarrierServiceImpl calls
--      UspsDirectConnector.voidShipment which returns voided=true with
--      status="VOID_PENDING_RECONCILIATION" WITHOUT touching USPS.
--   2. VoidServiceImpl flips OrderTracking.status = 'VOIDED' (existing
--      behaviour); ORDER PAGE reflects the cancellation immediately.
--   3. Nightly (or on-demand) UspsDirectVoidReconciliationService parses
--      the eVS Refund report USPS produces and cross-checks each void:
--        - Refund APPROVED → mark void_reconciliation_status =
--          'RECONCILED_APPROVED' (local status stays VOIDED).
--        - Refund DENIED → mark 'RECONCILED_DENIED' and flip
--          status = 'VOID_FAILED' so the operator sees the reversal
--          (label was scanned in transit → USPS refuses the refund).
--        - PENDING → leave everything alone; try again next batch.
--
-- Adds two columns to order_label_tracking:
--
--   void_reconciliation_status VARCHAR(30) — enum-like text column:
--       NULL                    → never reconciled yet (or not a void)
--       'PENDING'               → optimistic void queued, USPS unheard
--       'RECONCILED_APPROVED'   → USPS refunded the postage
--       'RECONCILED_DENIED'     → USPS refused the refund (label
--                                 scanned in transit, most commonly)
--   void_reconciliation_checked_at TIMESTAMP — when the reconciliation
--       job last touched this row. Nullable until first reconciliation.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md — Hibernate
-- creates the columns from the entity on a truly fresh DB; the guards
-- skip the ALTER in that case.

DO $$
BEGIN
    IF to_regclass('public.order_label_tracking') IS NOT NULL THEN
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'order_label_tracking'
                   AND column_name = 'void_reconciliation_status') THEN
            ALTER TABLE public.order_label_tracking
                ADD COLUMN void_reconciliation_status VARCHAR(30);
            COMMENT ON COLUMN public.order_label_tracking.void_reconciliation_status IS
                'PR-D USPS_DIRECT — void-reconciliation state. NULL = not reconciled. PENDING = optimistic void queued. RECONCILED_APPROVED = USPS refunded. RECONCILED_DENIED = USPS refused (label scanned in transit); status is flipped to VOID_FAILED.';
        END IF;

        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'order_label_tracking'
                   AND column_name = 'void_reconciliation_checked_at') THEN
            ALTER TABLE public.order_label_tracking
                ADD COLUMN void_reconciliation_checked_at TIMESTAMP;
            COMMENT ON COLUMN public.order_label_tracking.void_reconciliation_checked_at IS
                'PR-D USPS_DIRECT — timestamp of the most recent reconciliation attempt on this row. NULL until the reconciliation job has first seen it.';
        END IF;
    END IF;
END $$;
