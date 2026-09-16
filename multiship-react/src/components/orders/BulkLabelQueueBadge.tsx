import { useEffect, useState } from 'react'
import { FiClock } from 'react-icons/fi'
import {
  uspsLabelQueueService,
  type UspsLabelQueueMetrics,
} from '../../api/uspsLabelQueueService'
import { isAbortError } from '../../api/apiClient'
import { formatQueueDuration } from './uspsQueueFormat'

/**
 * PR-F1 Agent-2 — small pill component that surfaces USPS Direct
 * queue depth + estimated wait inline in the bulk-label modal (and
 * anywhere else operators need a "your USPS labels are queued" hint).
 *
 * <p>Behavior:
 * <ul>
 *   <li>Fetches {@link uspsLabelQueueService.getMetrics} on mount +
 *       every {@code refreshMs} (default 30s) while mounted.</li>
 *   <li>Renders NOTHING when depth is 0 — no rate-limit worry means
 *       no pill. Prevents an always-on chip that pretends there's
 *       queue pressure when there isn't.</li>
 *   <li>Also renders nothing on the first render before the initial
 *       fetch resolves (avoids a "0 in queue" flash).</li>
 *   <li>Handles API errors silently (logs at debug + hides itself).
 *       Queue metrics are cosmetic — a Redis blip shouldn't blow up
 *       the modal.</li>
 * </ul>
 *
 * <p>Pass {@code tenantCode} to scope the metrics to a single
 * tenant; omit for the platform-wide view (the default admin
 * dashboard use case).
 *
 * <p>Duration formatting lives in {@code ./uspsQueueFormat.ts} so
 * both this component and the modal's warning banner share the same
 * copy (react-refresh needs component files to export only components).
 */
export interface BulkLabelQueueBadgeProps {
  /** Scope to one tenant's metrics; omit for platform-wide. */
  tenantCode?: string
  /** Poll interval in ms. Defaults to 30_000 (30s). Set to 0 to
   *  disable auto-refresh (only fetch on mount). */
  refreshMs?: number
}

export default function BulkLabelQueueBadge({
  tenantCode,
  refreshMs = 30_000,
}: BulkLabelQueueBadgeProps) {
  const [metrics, setMetrics] = useState<UspsLabelQueueMetrics | null>(null)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      try {
        const resp = await uspsLabelQueueService.getMetrics(tenantCode)
        if (cancelled) return
        // ApiResponse<T> wraps the payload in .data — mirror the
        // pattern every other service in this repo uses.
        setMetrics(resp?.data ?? null)
      } catch (err) {
        if (isAbortError(err)) return
        // Queue metrics are cosmetic. Log at debug (not console.error)
        // so a Redis blip doesn't drown real error logs — same call-
        // level classification the apiClient uses for 4xx flows.
        console.debug('BulkLabelQueueBadge metrics fetch failed', err)
        if (!cancelled) setMetrics(null)
      }
    }

    void load()
    if (refreshMs > 0) {
      const timer = window.setInterval(() => {
        void load()
      }, refreshMs)
      return () => {
        cancelled = true
        window.clearInterval(timer)
      }
    }
    return () => {
      cancelled = true
    }
  }, [tenantCode, refreshMs])

  // Nothing to show: pre-first-fetch, error state, or empty queue.
  if (metrics == null) return null
  if (metrics.depth <= 0) return null

  const label = tenantCode ? `in your USPS queue` : `in USPS queue`
  const waitText = formatQueueDuration(metrics.estimatedWaitSeconds)

  return (
    <span
      role="status"
      aria-live="polite"
      data-testid="usps-queue-badge"
      className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[11px] font-semibold text-amber-800"
    >
      <FiClock className="h-3 w-3" aria-hidden="true" />
      <span>
        {metrics.depth} {label}
      </span>
      <span className="text-amber-600" aria-hidden="true">
        ·
      </span>
      <span className="tabular-nums font-normal text-amber-700">
        est. {waitText}
      </span>
    </span>
  )
}
