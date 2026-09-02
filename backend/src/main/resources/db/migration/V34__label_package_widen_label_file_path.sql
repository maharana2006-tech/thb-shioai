-- label_package.label_file_path holds the same content class V31 already
-- widened on order_label_tracking: either a signed carrier URL (500-2000
-- chars on FedEx) or, since PR #550's LabelBytesPersister, the base64-
-- encoded label bytes (10 KB - 200 KB). The varchar(500) cap made every
-- re-ship of a multi/single-piece order fail its piece-row insert with
-- "value too long for type character varying(500)" AFTER the carrier had
-- already produced the label. Widen alongside tracking_url, which carries
-- the same deep-link + token class of unbounded text.

ALTER TABLE label_package ALTER COLUMN label_file_path TYPE text;
ALTER TABLE label_package ALTER COLUMN tracking_url TYPE text;
