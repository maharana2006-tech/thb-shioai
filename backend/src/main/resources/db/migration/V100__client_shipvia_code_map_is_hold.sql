-- V98 — client_shipvia_code_map.is_hold
--
-- Marks an ERP ship-via code as a "hold" indicator for the NDS prefill
-- flow. When ANY row for (client_code, erp_code) has is_hold=true the
-- prefill blocks the order with a BLOCKED status.
--
-- Replaces the hardcoded {@code "HLD".equalsIgnoreCase(shipvia)} check
-- in NdsShipmentLookupService with a data-driven per-client rule.
--
-- Fresh-DB safe: to_regclass guard + DO block.
-- Seeds is_hold=true on every existing row whose erp_code IS 'HLD' so
-- current behavior is preserved on rollout.

DO $$
BEGIN
    IF to_regclass('public.client_shipvia_code_map') IS NULL THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'client_shipvia_code_map'
          AND column_name = 'is_hold'
    ) THEN
        ALTER TABLE public.client_shipvia_code_map
            ADD COLUMN is_hold BOOLEAN NOT NULL DEFAULT FALSE;
        COMMENT ON COLUMN public.client_shipvia_code_map.is_hold IS
            'V98 — when true, NDS prefill blocks orders with this SHIPVIA_CD as "on hold".';

        UPDATE public.client_shipvia_code_map
           SET is_hold = TRUE
         WHERE UPPER(erp_code) = 'HLD';
    END IF;
END
$$;
