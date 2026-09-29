-- What the carrier said about a row when it was checked ("UPS confirmed UPS Ground (U11) · 2 business days").
-- It used to ride in the warnings list, so anything counting or exporting warnings counted a confirmation.
ALTER TABLE import_batch_row ADD COLUMN IF NOT EXISTS carrier_note TEXT;
