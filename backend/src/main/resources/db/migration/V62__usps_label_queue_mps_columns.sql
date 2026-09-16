-- V62 - USPS_DIRECT PR-F2: MPS parent/sequence columns on usps_label_queue.
--
-- Scenario A ("MPS explosion") in docs/usps-direct-integration.md:
-- ONE order with N packages produces N sequential USPS label calls
-- (USPS has no batch label endpoint). PR-F1 gave us a persistent queue
-- with UNIQUE(shipment_id); PR-F2 layers "these N rows all belong to
-- ORDER X" on top so admin surfaces can aggregate progress across the
-- N pieces via GROUP BY parent_order_no.
--
-- Row shape after V62:
--   parent_order_no  BIGINT NULL - the ORDER number the N pieces
--                                  descend from. NULL = single-label
--                                  enqueue (PR-F1 shape unchanged).
--   sequence_number  INT    NULL - 1-based position within the parent
--                                  order's MPS split. NULL for non-MPS
--                                  rows.
--
-- The existing UNIQUE(shipment_id) constraint stays intact. MPS rows
-- get synthetic negative shipmentIds derived from the parent order +
-- sequence (see UspsMpsSplitterService); single-label enqueue rows
-- keep using orderNo verbatim. Negative vs positive shipmentIds are a
-- deliberate discriminator so the two paths cannot collide even if a
-- real orderNo were to numerically match a synthetic MPS id.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md - the
-- to_regclass guard skips the ALTER when Hibernate has already created
-- the columns from the entity on a truly fresh DB.

DO $$
BEGIN
    IF to_regclass('public.usps_label_queue') IS NOT NULL THEN
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'usps_label_queue'
                   AND column_name = 'parent_order_no') THEN
            ALTER TABLE public.usps_label_queue
                ADD COLUMN parent_order_no BIGINT;
            COMMENT ON COLUMN public.usps_label_queue.parent_order_no IS
                'PR-F2 - MPS parent order number. NULL = single-label enqueue (PR-F1 shape); populated = piece of an N-piece MPS order. Used to aggregate progress via GROUP BY parent_order_no in UspsMpsProgressDTO.';
        END IF;

        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'usps_label_queue'
                   AND column_name = 'sequence_number') THEN
            ALTER TABLE public.usps_label_queue
                ADD COLUMN sequence_number INT;
            COMMENT ON COLUMN public.usps_label_queue.sequence_number IS
                'PR-F2 - Position within the MPS parent order (1-based). NULL for non-MPS rows. Preserves ordering when the queue processor picks pieces.';
        END IF;

        -- Partial index: only rows that ARE MPS pieces need to be looked
        -- up by parent. Single-label enqueue rows (NULL) skip the index
        -- so it stays cheap on the write path (99% of the queue).
        CREATE INDEX IF NOT EXISTS idx_usps_queue_parent
            ON public.usps_label_queue (parent_order_no)
            WHERE parent_order_no IS NOT NULL;
    END IF;
END $$;
