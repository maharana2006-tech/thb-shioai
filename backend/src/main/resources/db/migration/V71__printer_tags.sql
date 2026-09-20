-- V71 - Printer R8a: per-printer tag chips.
--
-- Free-form tags for grouping / filtering / labelling registered
-- printers (e.g. "warehouse-north", "high-volume", "backup-only"). The
-- FE offers autocomplete off the distinct-tag list; there is no preset
-- catalog table (user's R8 decision).
--
-- Cascade delete: dropping a printer drops its tag rows.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('public.printer_tags') IS NULL THEN
        CREATE TABLE public.printer_tags (
            id          BIGSERIAL PRIMARY KEY,
            printer_id  BIGINT       NOT NULL,
            -- Lowercased for stable dedupe / autocomplete matching.
            -- The FE title-cases on display if needed.
            tag         VARCHAR(60)  NOT NULL,
            created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT fk_printer_tags_printer
                FOREIGN KEY (printer_id) REFERENCES public.printer(id)
                ON DELETE CASCADE,
            CONSTRAINT uk_printer_tags_printer_tag
                UNIQUE (printer_id, tag)
        );

        -- Autocomplete lookup: distinct tags. GIN would be overkill;
        -- a btree on (tag) covers the DISTINCT query + prefix search.
        CREATE INDEX idx_printer_tags_tag ON public.printer_tags (tag);

        COMMENT ON TABLE public.printer_tags IS
            'PR-Printer-R8a: free-form tags for grouping registered printers. FE autocompletes off the distinct-tag list; no preset catalog.';

        COMMENT ON COLUMN public.printer_tags.tag IS
            'Lowercased for dedupe/autocomplete stability. FE may title-case on display.';
    END IF;
END
$$;
