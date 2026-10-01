-- Returns F12 — add Order.original_order_no (INTEGER) linking a return
-- label back to its outbound counterpart. Nullable because outbound
-- orders + legacy returns have no linkage. The audit calls out two
-- payoffs: a "return of #12345" chip on the orders list and reprinting
-- the original commercial invoice from the return row.
--
-- Deliberately not a REFERENCES constraint — label_batch's PK is
-- compound (order_no, order_suffix) and most returns point at suffix 0
-- outbound rows anyway; a strict FK would also fail cascade-delete
-- gymnastics the audit never asked for. App-layer existence check at
-- the manual-shipment boundary (CarrierServiceImpl.canonOriginalOrder)
-- drops invalid refs to null the same way return_reason / rma_number
-- do, so a stale typed value doesn't block the label.

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NOT NULL THEN
        ALTER TABLE label_batch
            ADD COLUMN IF NOT EXISTS original_order_no INTEGER;
    END IF;
END $$;
