-- BUG-012 — MKL246 and THB000 ship-from Address line 1 ended with a
-- stray comma ("8191 Logistics Dr,"). The field is printed on every
-- label and sent to carriers; USPS/FedEx accepted it but it looks
-- unprofessional. Strip trailing comma + whitespace from every client's
-- street line one so no new label carries the typo.
--
-- Idempotent: regexp_replace is a no-op on strings without the pattern.
DO $$
BEGIN
    IF to_regclass('public.clients') IS NOT NULL THEN
        UPDATE clients
           SET address_line1 = regexp_replace(address_line1, '[,\s]+$', '')
         WHERE address_line1 ~ '[,\s]$';
    END IF;
END $$;
