-- Every import keeps its rows in import_batch_row (file imports used rows_json:
-- one text blob, read and rewritten whole — 58 MB for a 50k-row file). The
-- batch page pages, searches and filters them in SQL; an edit writes the rows
-- that changed.

-- The file's own values, as typed: a phone longer than 20 characters, a
-- 3-letter currency cut to 5, a carrier message past 500 or a weight with 3
-- decimals must survive to be shown and corrected, not fail the save or be
-- silently rounded (which would hide the "at most 2 decimal places" error).
ALTER TABLE import_batch_row
    ALTER COLUMN order_ref TYPE TEXT,
    ALTER COLUMN bill_to TYPE TEXT,
    ALTER COLUMN reference TYPE TEXT,
    ALTER COLUMN client_code TYPE TEXT,
    ALTER COLUMN warehouse_code TYPE TEXT,
    ALTER COLUMN recipient_name TYPE TEXT,
    ALTER COLUMN recipient_company TYPE TEXT,
    ALTER COLUMN recipient_phone TYPE TEXT,
    ALTER COLUMN recipient_email TYPE TEXT,
    ALTER COLUMN address_line1 TYPE TEXT,
    ALTER COLUMN address_line2 TYPE TEXT,
    ALTER COLUMN city TYPE TEXT,
    ALTER COLUMN state TYPE TEXT,
    ALTER COLUMN postal_code TYPE TEXT,
    ALTER COLUMN country_code TYPE TEXT,
    ALTER COLUMN carrier_code TYPE TEXT,
    ALTER COLUMN service_type TYPE TEXT,
    ALTER COLUMN account_number TYPE TEXT,
    ALTER COLUMN package_type TYPE TEXT,
    ALTER COLUMN weight_unit TYPE TEXT,
    ALTER COLUMN dim_unit TYPE TEXT,
    ALTER COLUMN currency TYPE TEXT,
    ALTER COLUMN incoterms TYPE TEXT,
    ALTER COLUMN hs_code TYPE TEXT,
    ALTER COLUMN country_of_origin TYPE TEXT,
    ALTER COLUMN item_sku TYPE TEXT,
    ALTER COLUMN item_description TYPE TEXT,
    ALTER COLUMN generated_order_no TYPE TEXT,
    ALTER COLUMN generated_tracking_number TYPE TEXT,
    ALTER COLUMN generated_status TYPE TEXT,
    ALTER COLUMN generated_message TYPE TEXT,
    ALTER COLUMN weight TYPE NUMERIC,
    ALTER COLUMN length TYPE NUMERIC,
    ALTER COLUMN width TYPE NUMERIC,
    ALTER COLUMN height TYPE NUMERIC,
    ALTER COLUMN item_unit_value TYPE NUMERIC;

-- The client's own ship via code and what the mapping made of it; the
-- carrier's tracking page for a generated label.
ALTER TABLE import_batch_row ADD COLUMN IF NOT EXISTS ship_via_code TEXT;
ALTER TABLE import_batch_row ADD COLUMN IF NOT EXISTS ship_via_note TEXT;
ALTER TABLE import_batch_row ADD COLUMN IF NOT EXISTS tracking_url TEXT;

-- A batch's rows in order (every page read), and orderRef look-ups across
-- imports (staging's "already in Import history").
CREATE INDEX IF NOT EXISTS ix_import_batch_row_batch_row ON import_batch_row (import_batch_id, row_number);
CREATE INDEX IF NOT EXISTS ix_import_batch_row_ref ON import_batch_row (UPPER(order_ref));

-- Deleting an import (Empty Trash, retention) takes its rows along; the
-- Hibernate-made key did not, so a WMS batch with rows could not be deleted.
DO $$
DECLARE c TEXT;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'import_batch_row'::regclass AND contype = 'f' LOOP
        EXECUTE format('ALTER TABLE import_batch_row DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE import_batch_row ADD CONSTRAINT fk_import_batch_row_batch
    FOREIGN KEY (import_batch_id) REFERENCES import_batch (id) ON DELETE CASCADE;
