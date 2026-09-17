-- V66 - Perf audit PR-P2 (finding PERF-M11): composite index for tenant-scoped
-- audit-log range queries.
--
-- Symptom (from docs/perf-audit.md):
--   AuditLogRepositoryCustomImpl.buildScopeClause uses
--       WHERE ... UPPER(a.clientCode) = UPPER(:scope)
--         AND a.createdAt >= :since AND a.createdAt <= :until
--   The existing idx_audit_log_client_code indexes clientCode alone.
--   The range filter on created_at + equality on client_code needs a
--   composite index to avoid falling back to a table scan for the
--   time-window filter after the equality seek.
--
-- Row shape after V66:
--   idx_audit_log_client_code_created ON audit_log (client_code, created_at DESC)
--       — the DESC on created_at lets ORDER BY created_at DESC / DESC LIMIT
--         page-loading queries use the index for ordering AND filtering.
--         audit_log grows unbounded (retention policy lives outside the
--         index) so this pays for itself the first day it lands.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md - the
-- to_regclass guard skips the CREATE INDEX when Hibernate hasn't yet
-- created the table.

DO $$
BEGIN
    IF to_regclass('public.audit_log') IS NOT NULL THEN
        -- Not CONCURRENTLY because Flyway runs each migration in a
        -- transaction and CREATE INDEX CONCURRENTLY isn't allowed inside
        -- one. On the current dev DB (< 100k rows) this is a sub-second
        -- exclusive-lock op; large prod deploys should rebuild with
        -- CONCURRENTLY out-of-band before running V66, then let the
        -- IF NOT EXISTS below no-op.
        CREATE INDEX IF NOT EXISTS idx_audit_log_client_code_created
            ON public.audit_log (client_code, created_at DESC);
        COMMENT ON INDEX public.idx_audit_log_client_code_created IS
            'PR-P2 (PERF-M11): composite (client_code, created_at DESC) so tenant-scoped audit-log range queries can seek by scope + order by time in a single index scan. Complements the pre-P2 idx_audit_log_client_code which only covered the equality half.';
    END IF;
END
$$;
