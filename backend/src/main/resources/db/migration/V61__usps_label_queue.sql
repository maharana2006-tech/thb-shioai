-- V61 - USPS_DIRECT PR-F: persistent label queue.
--
-- Backs the queue-core slice of the USPS_DIRECT scale-hardening track
-- (see docs/usps-direct-integration.md PR-F). USPS' label API is capped
-- at ~60 req/hour per platform OAuth app; every tenant on this platform
-- shares that quota. When operators trigger a 1000-piece MPS shipment
-- (scenario A), a 1000-order bulk-label run (scenario B), or 1000+
-- packages across many tenants concurrently (scenario C), we cannot
-- burst the labels to USPS. Instead each label request is persisted in
-- this queue and the UspsLabelQueueProcessor drains it at 55 req/hour
-- (safety margin under USPS' 60/hour ceiling) with per-tenant fair-
-- sharing so one big tenant cannot starve the others.
--
-- Row lifecycle:
--   QUEUED     -> enqueued by Agent 2's wiring class when a USPS label
--                 write path lands. Processor sees it on next tick.
--   PROCESSING -> processor claimed the row and invoked the wired
--                 LabelProcessCallback; USPS request in flight.
--   DONE       -> callback succeeded; tracking_number populated,
--                 completed_at stamped.
--   FAILED     -> callback threw; last_error captures the message,
--                 retry_count++ for backoff / alerting.
--   CANCELLED  -> admin or operator cancelled the row before it
--                 started processing. cancel() no-ops on PROCESSING/
--                 DONE/FAILED per the service contract.
--
-- UNIQUE (shipment_id) guarantees at-most-one live queue entry per
-- shipment; re-enqueueing after a CANCELLED requires the caller to
-- clean up (delete the stale row or use a new shipment id). This is
-- deliberate: the same shipment silently entering the queue twice is
-- a bug we want to catch (fires as a DB unique violation surfaced by
-- UspsLabelQueueService.enqueue()).
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md - the CREATE
-- TABLE / CREATE INDEX statements are IF NOT EXISTS and reference only
-- the new table.

CREATE TABLE IF NOT EXISTS usps_label_queue (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_code         VARCHAR(64)  NOT NULL,
    shipment_id         BIGINT       NOT NULL,
    priority            INT          NOT NULL DEFAULT 100,
    status              VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    enqueued_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    retry_count         INT          NOT NULL DEFAULT 0,
    last_error          TEXT,
    tracking_number     VARCHAR(64),
    CONSTRAINT uk_usps_label_queue_shipment UNIQUE (shipment_id)
);

COMMENT ON TABLE usps_label_queue IS
    'USPS_DIRECT PR-F persistent label queue - one row per USPS label request. Processor drains at 55/hour with per-tenant fair-share. See docs/usps-direct-integration.md PR-F.';
COMMENT ON COLUMN usps_label_queue.tenant_code IS
    'Tenant / client code owning this label request. Used for per-tenant fair-share slicing in UspsLabelQueueFairScheduler.';
COMMENT ON COLUMN usps_label_queue.shipment_id IS
    'Local shipment identifier (order_label_tracking.id or shipment.id) - the row the callback wraps a USPS createLabel around. Unique.';
COMMENT ON COLUMN usps_label_queue.priority IS
    'Lower value = higher priority (mirrors POSIX nice). Default 100; admin surface may set <=10 for urgent-tenant jumps in the fair scheduler.';
COMMENT ON COLUMN usps_label_queue.status IS
    'QUEUED | PROCESSING | DONE | FAILED | CANCELLED. State machine documented in V61 header.';
COMMENT ON COLUMN usps_label_queue.retry_count IS
    'Number of times the callback has thrown against this row. Not auto-retried yet (Agent 2 wires the retry policy); populated for observability + future retry loop.';
COMMENT ON COLUMN usps_label_queue.tracking_number IS
    'Populated on DONE; the USPS-assigned tracking number from the successful createLabel response.';

-- Processor pick-next hot path: findByStatusOrderByPriorityAscEnqueuedAtAsc(QUEUED)
-- and per-tenant depth counts. Composite index covers both.
CREATE INDEX IF NOT EXISTS idx_usps_queue_status_tenant
    ON usps_label_queue (status, tenant_code, priority, enqueued_at);
