import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * PR-F1 Agent-2 — typed API client for the USPS Direct persistent
 * label queue (Agent-1's admin controller lives at
 * {@code /admin/usps-direct/queue/*}).
 *
 * <p>Two callers today:
 * <ul>
 *   <li>{@link BulkLabelQueueBadge} — polls {@link #getMetrics} every
 *       30s while mounted; used to show "12 in USPS queue · est. 45m"
 *       inside the bulk-label modal.</li>
 *   <li>Future admin dashboard — {@link #getItems} +
 *       {@link #cancel} for operator triage. Not wired in this PR.</li>
 * </ul>
 *
 * <p>Types mirror Agent-1's DTOs. Any field Agent-1 renames at merge
 * time needs updating here; the safest merge test is to re-run
 * {@code npx vitest run BulkLabelQueueBadge} against the merged
 * branch and confirm the shape assertions still hold.
 *
 * <p>PR-F2 (this PR) — adds the MPS-progress endpoint typing +
 * {@link #getMpsProgress} method, consumed by
 * {@code useMpsProgress} / {@code MpsProgressCard} so operators
 * running "ONE order with 1000 pieces" MPS shipments see aggregate
 * progress without walking child rows.
 */

/**
 * Platform-wide (or tenant-scoped when {@link #tenantCode} is set)
 * snapshot returned by {@code GET /admin/usps-direct/queue/metrics}.
 * The {@code totalPerHourCap} is authoritative (backend reads USPS's
 * 60/hr platform limit and applies a 55/hr safety margin) — the FE
 * surfaces the number verbatim in the warning banner so operators
 * don't guess.
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
}
