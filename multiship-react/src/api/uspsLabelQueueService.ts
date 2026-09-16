import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * PR-F1 Agent-2 — typed API client for the USPS Direct persistent
 * label queue (Agent-1's admin controller lives at
 * {@code /admin/usps-direct/queue/*}).
 *
 * <p>Callers today:
 * <ul>
 *   <li>{@link BulkLabelQueueBadge} — polls {@link #getMetrics} every
 *       30s while mounted; used to show "12 in USPS queue · est. 45m"
 *       inside the bulk-label modal.</li>
 *   <li>PR-F2 — {@link #getMpsProgress} feeds {@code useMpsProgress} +
 *       {@code MpsProgressCard} for "ONE order with 1000 pieces" MPS
 *       aggregate progress without walking child rows.</li>
 *   <li>PR-F4 (this PR) — {@link #getDashboard} + the three per-panel
 *       endpoints back the admin dashboard page at
 *       {@code /settings/usps-direct/dashboard}. Composite endpoint is
 *       what the page uses on every tick; per-panel endpoints exist so
 *       future partial-refresh widgets can reuse the typed client.</li>
 * </ul>
 *
 * <p>Types mirror Agent-1's DTOs. Any field Agent-1 renames at merge
 * time needs updating here; the safest merge test is to re-run
 * {@code npx vitest run BulkLabelQueueBadge UspsDirectDashboardPage}
 * against the merged branch and confirm the shape assertions still hold.
 */

/**
 * Platform-wide (or tenant-scoped when {@link #tenantCode} is set)
 * snapshot returned by {@code GET /admin/usps-direct/queue/metrics}.
 * The {@code totalPerHourCap} is authoritative (backend reads USPS's
 * 60/hr platform limit and applies a 55/hr safety margin) — the FE
 * surfaces the number verbatim in the warning banner so operators
 * don't guess.
 *
 * <p>PR-F4 adds an optional {@code perTenantDepth} map so the
 * dashboard's queue panel can rank tenants by depth. Backend leaves the
 * field unset on the tenant-scoped view; on the platform-wide view it
 * populates it with {@code tenantCode → depth}. The FE guards against
 * both missing map and empty map.
 */
export interface UspsLabelQueueMetrics {
  /** PENDING queue rows (waiting to be picked up). */
  depth: number
  /** RUNNING queue rows (actively hitting USPS right now). */
  processing: number
  /** Projected wait for the tail of the queue at the current rate,
   *  in seconds. */
  estimatedWaitSeconds: number
  /** Platform-wide per-hour cap (55 by default; the FE surfaces this
   *  verbatim in the warning banner so the number stays authoritative
   *  on the backend). Null on tenant-scoped metrics. */
  totalPerHourCap?: number | null
  /** Tenant scope, or {@code null} for the platform-wide view. */
  tenantCode?: string | null
  /** PR-F4 — per-tenant PENDING depth on the platform-wide view.
   *  Absent (undefined) or empty on tenant-scoped metrics + on very
   *  early boots before the backend has any tenant activity. */
  perTenantDepth?: Record<string, number> | null
}

/**
 * One persistent-queue row surfaced by the admin listing endpoint. Not
 * consumed by {@link BulkLabelQueueBadge} — kept here so a future
 * admin dashboard can share the same typed client without duplicating
 * the shape.
 */
export interface UspsLabelQueueItem {
  id: number
  tenantCode: string
  shipmentId: number
  /** Free-form status; expected values include PENDING, RUNNING,
   *  DONE, FAILED, CANCELLED. */
  status: string
  retryCount: number
  createdAt: string | null
}

/**
 * Spring Data Page envelope — matches the response shape from the
 * paged listing endpoint. Kept minimal (only the fields we consume);
 * add more as callers need them.
 */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/**
 * PR-F2 — MPS progress payload from
 * {@code GET /admin/usps-direct/queue/mps-progress/{orderNo}}.
 *
 * <p>One "ONE order with N pieces" MPS ships through the USPS Direct
 * queue as N rows (one per piece); this endpoint aggregates them by
 * status so the FE can render "400 of 1000 · 40.0%" without walking
 * the child items itself.
 *
 * <p>404 on this endpoint means "no MPS queue items for this order"
 * (either the order isn't an MPS, or every child row has been GC'd);
 * the {@code useMpsProgress} hook translates that to a null progress
 * so the card renders its own empty state.
 */
export interface UspsMpsProgress {
  /** The MPS parent order number this progress belongs to. */
  parentOrderNo: number
  /** Total pieces (== child queue row count) across all statuses. */
  totalPieces: number
  /**
   * Per-status counts. Backend omits keys with zero rows, so every
   * bucket is optional; sum should equal {@link #totalPieces}.
   */
  byStatus: Partial<
    Record<'QUEUED' | 'PROCESSING' | 'DONE' | 'FAILED' | 'CANCELLED', number>
  >
  /** 0-100, rounded to one decimal by the backend. */
  percentComplete: number
  /** ISO datetime; null until the first PROCESSING row exists. */
  estimatedCompletionAt: string | null
  /** ISO datetime; null until the first DONE row exists. */
  startedAt: string | null
  /** First 20 completed tracking numbers (backend caps the list). */
  trackingNumbers: string[]
}

/**
 * PR-F4 Agent-2 — USPS quota (55/hr token-bucket) headroom snapshot.
 *
 * <p>{@code hourlyCap} is authoritative (backend applies USPS's 55/hr
 * safety margin over their 60/hr platform limit). The FE surfaces the
 * number verbatim so the cap doesn't drift between BE and FE.
 *
 * <p>{@code utilizationPercent} is precomputed on the backend so the
 * FE can't miscalculate under partial-token accounting;
 * {@code nextReplenishInSeconds} is a client-side hint for the
 * countdown display (the FE decrements locally between fetches so the
 * countdown stays smooth).
 */
export interface UspsQuotaHeadroom {
  /** Platform-wide hourly cap (55 by default). */
  hourlyCap: number
  /** Tokens available right now (0..hourlyCap). */
  remainingTokens: number
  /** 0-100 utilization percentage (precomputed). */
  utilizationPercent: number
  /** ISO datetime of the last token replenish tick. */
  lastReplenishAt: string
  /** Seconds until the next replenish tick (backend snapshot). */
  nextReplenishInSeconds: number
}

/**
 * PR-F4 Agent-2 — one hour-bucket in the retry/failure history.
 * {@code hourStart} is the ISO datetime marking the start of that hour;
 * buckets are pre-sorted oldest-first by the backend so the FE draws
 * left-to-right chronologically.
 */
export interface UspsRetryBucketEntry {
  hourStart: string
  attempts: number
  retries: number
  failures: number
}

/**
 * PR-F4 Agent-2 — full retry/failure history payload.
 * {@code hoursLookback} echoes the request so the FE can render the
 * axis label without keeping the original query.
 */
export interface UspsRetryBuckets {
  hoursLookback: number
  buckets: UspsRetryBucketEntry[]
}

/**
 * PR-F4 Agent-2 — void-shipment reconciliation rollup for the last N
 * days.
 *
 * <p>A voided shipment progresses:
 * {@code notYetReconciled} → {@code reconciledApproved} (USPS credited
 * the refund) OR {@code reconciledDenied} (USPS refused, usually
 * because the label was already scanned).
 *
 * <p>{@code pendingRefundValue} is the sum of expected refund amounts
 * still in flight (voided shipments not yet reconciled by USPS).
 */
export interface UspsReconciliationRollup {
  lookbackDays: number
  voidedShipmentsInWindow: number
  reconciledApproved: number
  reconciledDenied: number
  notYetReconciled: number
  /** ISO datetime of the most recent reconciliation run, or null if
   *  the reconciler hasn't run yet. */
  lastReconciliationAt: string | null
  pendingRefundValue: number
  /** ISO 4217 currency code (typically USD). */
  currency: string
}

/**
 * PR-F4 Agent-2 — composite dashboard payload. All four panel DTOs in
 * one envelope so the dashboard page fires exactly one request per
 * refresh tick.
 */
export interface UspsDashboardMetrics {
  /** ISO datetime when this snapshot was generated on the backend. */
  generatedAt: string
  queue: UspsLabelQueueMetrics
  quota: UspsQuotaHeadroom
  retries: UspsRetryBuckets
  reconciliation: UspsReconciliationRollup
}

export const uspsLabelQueueService = {
  /**
   * Platform-wide snapshot; pass a {@code tenantCode} to scope to
   * one tenant. When both callers exist we could parameterise a
   * single endpoint, but keeping the tenant param optional here
   * lets the badge stay a one-liner regardless of view scope.
   */
  getMetrics: (tenantCode?: string) => {
    const suffix = tenantCode
      ? `?tenantCode=${encodeURIComponent(tenantCode)}`
      : ''
    return apiClient.get<ApiResponse<UspsLabelQueueMetrics>>(
      `/admin/usps-direct/queue/metrics${suffix}`,
    )
  },

  /**
   * Paged listing of queue rows. Used by the (not-yet-shipped) admin
   * triage page — kept here so the client stays a single source of
   * truth for the admin endpoints.
   */
  getItems: (page = 0, size = 20) =>
    apiClient.get<ApiResponse<Page<UspsLabelQueueItem>>>(
      `/admin/usps-direct/queue/items?page=${page}&size=${size}`,
    ),

  /**
   * Cancel a PENDING queue row. Returns 404 if the row is unknown,
   * 409 if the row already ran (Agent-1's controller decides the
   * exact response codes; we keep the FE thin).
   */
  cancel: (id: number) =>
    apiClient.delete<ApiResponse<void>>(
      `/admin/usps-direct/queue/items/${id}`,
    ),

  /**
   * PR-F2 — MPS aggregate progress for one parent order.
   *
   * <p>Backend replies:
   * <ul>
   *   <li>{@code 200} + {@link UspsMpsProgress} when the order has any
   *       USPS Direct queue rows (MPS or single-piece).</li>
   *   <li>{@code 404} when no rows exist for the order — the caller's
   *       hook maps that to a {@code null} progress so the card
   *       renders "No MPS progress found" instead of an error toast.</li>
   * </ul>
   *
   * <p>Auth: ADMIN OR USER — operators can watch their own MPS.
   */
  getMpsProgress: (orderNo: number) =>
    apiClient.get<ApiResponse<UspsMpsProgress>>(
      `/admin/usps-direct/queue/mps-progress/${orderNo}`,
    ),

  /**
   * PR-F4 Agent-2 — composite admin dashboard snapshot bundling all
   * four panels (queue depth, quota headroom, retry buckets, void
   * reconciliation) in one round-trip so the dashboard page renders
   * atomically instead of showing four independent skeletons.
   *
   * <p>Endpoint: {@code GET /admin/usps-direct/dashboard}. ADMIN only.
   *
   * <p>The individual endpoints below are still exposed for future
   * partial-refresh widgets (e.g. a "just refresh quota" button); the
   * dashboard page itself calls this composite on mount + on interval.
   */
  getDashboard: (opts: { lookbackHours?: number; lookbackDays?: number } = {}) => {
    const params = new URLSearchParams()
    if (opts.lookbackHours != null) {
      params.set('lookbackHours', String(opts.lookbackHours))
    }
    if (opts.lookbackDays != null) {
      params.set('lookbackDays', String(opts.lookbackDays))
    }
    const qs = params.toString()
    return apiClient.get<ApiResponse<UspsDashboardMetrics>>(
      `/admin/usps-direct/dashboard${qs ? `?${qs}` : ''}`,
    )
  },

  /**
   * PR-F4 Agent-2 — quota headroom only (55/hr USPS token-bucket).
   * Cheap; not called by the dashboard directly, kept for a future
   * lightweight widget or health-check probe.
   */
  getQuotaHeadroom: () =>
    apiClient.get<ApiResponse<UspsQuotaHeadroom>>(
      `/admin/usps-direct/dashboard/quota-headroom`,
    ),

  /**
   * PR-F4 Agent-2 — per-hour retry / failure buckets. Backend defaults
   * to a 24h lookback; expose the param so a future control can widen
   * or narrow the chart.
   */
  getRetryBuckets: (hoursLookback?: number) => {
    const qs = hoursLookback != null ? `?hoursLookback=${hoursLookback}` : ''
    return apiClient.get<ApiResponse<UspsRetryBuckets>>(
      `/admin/usps-direct/dashboard/retry-buckets${qs}`,
    )
  },

  /**
   * PR-F4 Agent-2 — void-shipment reconciliation rollup for the last
   * N days (defaults to 30 on the backend). Standalone endpoint kept
   * so a future "reconciliation-only" screen can reuse it.
   */
  getReconciliationRollup: (lookbackDays?: number) => {
    const qs = lookbackDays != null ? `?lookbackDays=${lookbackDays}` : ''
    return apiClient.get<ApiResponse<UspsReconciliationRollup>>(
      `/admin/usps-direct/dashboard/reconciliation-rollup${qs}`,
    )
  },
}
