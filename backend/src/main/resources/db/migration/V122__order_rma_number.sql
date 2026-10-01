-- Returns F10 — add Order.rma_number (VARCHAR 60) for modern-returns
-- parity. The audit calls it "common modern-returns feature; not in
-- ShipX" — operators tag incoming returns with an RMA they issued, and
-- the number needs to reach the carrier label so warehouse staff can
-- reconcile when the box arrives.
--
-- Carrier wire (connector reference-slot swap) is a deliberate follow-
-- up — this migration + the DTO threading let the data reach the
-- connectors; which slot each carrier gets it in belongs in its own PR
-- after ops picks (slot 2 overrides DEPT for returns vs. new slot).

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NOT NULL THEN
        ALTER TABLE label_batch
            ADD COLUMN IF NOT EXISTS rma_number VARCHAR(60);
    END IF;
END $$;
