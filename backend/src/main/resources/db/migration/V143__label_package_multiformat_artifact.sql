-- Stage 2 of ZPL-first (2026-10-11): pre-render + store ZPL/PDF/PNG for
-- every new label at generate-time so the /label/{zpl,pdf,png} endpoints
-- are zero-conversion reads. label_file_path stays as the carrier's
-- original artifact for audit / debugging; the three new columns are
-- the derived representations.
--
-- No backfill — existing rows keep label_file_path only; their
-- /label/{pdf,png} endpoints continue to run on-demand conversion via
-- ZebrashRenderer / ZebrashPdfService (unchanged). Backfill can land
-- later as an admin-triggered batch job.
--
-- Idempotent (see docs/flyway-fresh-db-guard-pattern.md).
DO $$
BEGIN
    IF to_regclass('public.label_package') IS NOT NULL THEN
        ALTER TABLE label_package
            ADD COLUMN IF NOT EXISTS zpl_data TEXT,
            ADD COLUMN IF NOT EXISTS pdf_data TEXT,
            ADD COLUMN IF NOT EXISTS png_data TEXT;
    END IF;
END $$;
