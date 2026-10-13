-- BUG-012 (R4 retest) — the retest confirmed MKL246's /orders/new ship-from
-- STILL reads "8191 Logistics Dr," because the FE populates the ship-from
-- block from the attached warehouse row, not from clients.address_line1.
-- V147 only cleaned the clients table; warehouse.line1 was missed, so the
-- same string kept leaking onto every new label.
--
-- Strip trailing comma + whitespace from both street lines on every
-- warehouse. Idempotent: regexp_replace is a no-op on strings without
-- the pattern.
DO $$
BEGIN
    IF to_regclass('public.warehouse') IS NOT NULL THEN
        UPDATE warehouse
           SET line1 = regexp_replace(line1, '[,\s]+$', '')
         WHERE line1 ~ '[,\s]$';
        UPDATE warehouse
           SET line2 = regexp_replace(line2, '[,\s]+$', '')
         WHERE line2 ~ '[,\s]$';
    END IF;
END $$;
