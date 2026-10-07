-- Perf P3 phase 0 — foundation for the long-tx phase-C split (PERF-B1,
-- PERF-M7, PERF-M8). CarrierServiceImpl.generateLabel + sibling paths
-- today hold a Postgres row lock across the 5-15s carrier HTTP RTT; the
-- planned fix splits that into [validate+lock] / [carrier HTTP, no tx] /
-- [persist, new tx], which needs a durable "IN_FLIGHT" marker so a
-- process crash between phases B and C leaves recoverable state.
--
-- This migration is additive only — no caller change ships with it.
-- The caller rewrite lands behind feature flag carrier.tx-split-phase-c
-- in P1-P4.
--
-- Column: in_flight_since
--   Set by phase A when the row is reserved for a carrier dispatch;
--   NULLed by phase C on success or failure. Non-NULL + older than the
--   sweeper threshold = "stuck" (process crashed or carrier never
--   responded) and InFlightTrackingSweeper surfaces it. P0 logs stuck
--   rows only; P5 flips the sweeper to resolve them.
--
-- Partial index: only non-NULL rows participate in the sweeper's scan;
-- the live table is dominated by rows where in_flight_since IS NULL
-- (every settled label), so a partial index keeps the sweeper cheap.

DO $$
BEGIN
    IF to_regclass('public.order_label_tracking') IS NULL THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'order_label_tracking'
          AND column_name  = 'in_flight_since'
    ) THEN
        ALTER TABLE order_label_tracking
            ADD COLUMN in_flight_since TIMESTAMPTZ NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname  = 'idx_olt_in_flight_since'
    ) THEN
        CREATE INDEX idx_olt_in_flight_since
            ON order_label_tracking (in_flight_since)
            WHERE in_flight_since IS NOT NULL;
    END IF;
END $$;
