-- The uploaded file's own column names, in order (one per line), so the validate
-- screen shows the file as it came: CLIENT_ID, ATTENTION, … for a client-layout file.
ALTER TABLE import_staging_upload ADD COLUMN IF NOT EXISTS file_columns TEXT;
