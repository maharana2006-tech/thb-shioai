-- Phase 0 of the Shipment & Label re-architecture: store the artifact's format
-- and provenance explicitly so readers stop sniffing magic bytes.
--   format: ZPL | PDF | GIF | PNG | UNKNOWN          (what the carrier gave us)
--   source: CARRIER_NATIVE (real ZPL) | CARRIER_RASTER (image/PDF) | UNKNOWN
ALTER TABLE label_package ADD COLUMN IF NOT EXISTS format VARCHAR(16);
ALTER TABLE label_package ADD COLUMN IF NOT EXISTS source VARCHAR(16);

-- One-time backfill from label_file_path: raw ^XA text, or base64 whose
-- leading chars decode to the magic (XlhB=^XA, JVBERi0=%PDF-, R0lGOD=GIF8,
-- iVBORw0KGgo=PNG). This is the last time the format is ever inferred;
-- every new row is written with it set.
UPDATE label_package SET format = CASE
    WHEN label_file_path LIKE '^XA%' OR label_file_path LIKE 'XlhB%' THEN 'ZPL'
    WHEN label_file_path LIKE 'JVBERi0%'                             THEN 'PDF'
    WHEN label_file_path LIKE 'R0lGOD%'                              THEN 'GIF'
    WHEN label_file_path LIKE 'iVBORw0KGgo%'                         THEN 'PNG'
    ELSE 'UNKNOWN' END
WHERE format IS NULL;

UPDATE label_package SET source = CASE format
    WHEN 'ZPL'     THEN 'CARRIER_NATIVE'
    WHEN 'UNKNOWN' THEN 'UNKNOWN'
    ELSE                'CARRIER_RASTER' END
WHERE source IS NULL;

ALTER TABLE label_package ALTER COLUMN format SET DEFAULT 'UNKNOWN';
ALTER TABLE label_package ALTER COLUMN source SET DEFAULT 'UNKNOWN';
ALTER TABLE label_package ALTER COLUMN format SET NOT NULL;
ALTER TABLE label_package ALTER COLUMN source SET NOT NULL;
ALTER TABLE label_package ADD CONSTRAINT chk_label_package_format
    CHECK (format IN ('ZPL', 'PDF', 'GIF', 'PNG', 'UNKNOWN'));
ALTER TABLE label_package ADD CONSTRAINT chk_label_package_source
    CHECK (source IN ('CARRIER_NATIVE', 'CARRIER_RASTER', 'UNKNOWN'));
