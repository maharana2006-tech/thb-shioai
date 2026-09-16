import { useCallback, useEffect, useRef, useState } from 'react'
import {
  FiAlertTriangle,
  FiPauseCircle,
  FiPlayCircle,
  FiRefreshCw,
} from 'react-icons/fi'
import {
  uspsLabelQueueService,
  type UspsDashboardMetrics,
} from '../api/uspsLabelQueueService'
import { isAbortError } from '../api/apiClient'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'
import QueueDepthPanel from '../components/settings/dashboard/QueueDepthPanel'
import QuotaHeadroomPanel from '../components/settings/dashboard/QuotaHeadroomPanel'
import RetryBucketsPanel from '../components/settings/dashboard/RetryBucketsPanel'
import ReconciliationRollupPanel from '../components/settings/dashboard/ReconciliationRollupPanel'

/**
 * PR-F4 Agent-2 — USPS Direct admin dashboard page.
 *
 * <p>Wired at {@code /settings/usps-direct/dashboard} inside
 * {@code AppRoutes.tsx} under the ADMIN-only {@code RequireRole}
 * wrapper (mirrors the {@code /settings/system} pattern). The
 * inline role guard here is defense-in-depth — see
 * {@code memory://design_fe_role_gating} (doctrine B).
 *
 * <p>Composes four panels off a single composite endpoint
 * ({@link uspsLabelQueueService.getDashboard}) so a refresh tick
 * fires exactly one round-trip rather than four. The individual
 * per-panel endpoints on the service are exposed for future partial-
 * refresh widgets (e.g. a dedicated quota-only chip) but are not
 * used by this page.
 *
 * <p>Auto-refresh defaults to on with a 30s interval — matches the
 * PR-F1 {@code BulkLabelQueueBadge} cadence so both surfaces feel
 * coherent to the ops team. The Auto toggle is persistent within the
 * page mount only (no localStorage; a page reload starts from the
 * on-by-default state, which is intentional so an operator who left
 * the page paused doesn't forget it's paused).
 */

const DEFAULT_REFRESH_MS = 30_000
const DEFAULT_LOOKBACK_HOURS = 24
const DEFAULT_LOOKBACK_DAYS = 30

export default function UspsDirectDashboardPage() {
  const { role } = useAppSession()
  const normalizedRole = normalizeRole(role)
  const isAdmin = normalizedRole === 'ADMIN'

  const [snapshot, setSnapshot] = useState<UspsDashboardMetrics | null>(null)
  const [loading, setLoading] = useState<boolean>(true)
  const [error, setError] = useState<string | null>(null)
  const [autoRefresh, setAutoRefresh] = useState<boolean>(true)
  const [refreshing, setRefreshing] = useState<boolean>(false)

  /**
   * Track whether the component is still mounted so a slow fetch
   * that resolves after unmount doesn't call setState. Also lets the
   * refresh button ignore a rapid double-click while the previous
   * fetch is still in flight.
   */
  const mountedRef = useRef(true)
  const inFlightRef = useRef(false)

  const load = useCallback(async () => {
    if (inFlightRef.current) return
    inFlightRef.current = true
    setRefreshing(true)
    try {
      const resp = await uspsLabelQueueService.getDashboard({
        lookbackHours: DEFAULT_LOOKBACK_HOURS,
        lookbackDays: DEFAULT_LOOKBACK_DAYS,
      })
      if (!mountedRef.current) return
      setSnapshot(resp?.data ?? null)
      setError(null)
    } catch (err) {
      if (isAbortError(err)) return
      if (!mountedRef.current) return
      const msg =
        err instanceof Error ? err.message : 'Failed to load dashboard.'
      setError(msg)
      // Keep the previous snapshot so operators still see something
      // useful during a transient blip; the error banner announces
      // that the data is stale.
    } finally {
      if (mountedRef.current) {
        setLoading(false)
        setRefreshing(false)
      }
      inFlightRef.current = false
    }
  }, [])

  // Initial fetch on mount. Skipped when the caller isn't ADMIN so a
  // deep-linked non-admin doesn't burn a request the backend would
  // 403 anyway (and, more importantly, so the access-denied banner
  // stays clean — no error toasts flashing behind it).
  useEffect(() => {
    mountedRef.current = true
    if (isAdmin) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- initial fetch drives loading/error/snapshot state
      void load()
    }
    return () => {
      mountedRef.current = false
    }
  }, [isAdmin, load])

  // Auto-refresh loop — only when the toggle is on AND the caller
  // is ADMIN. Same rationale as the mount fetch above.
  useEffect(() => {
    if (!isAdmin) return
    if (!autoRefresh) return
    const timer = window.setInterval(() => {
      void load()
    }, DEFAULT_REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [isAdmin, autoRefresh, load])

  // Defense-in-depth: RequireRole at the route level is the primary
  // gate, but if the page ever mounts outside a wrapper (e.g. deep-
  // link during a role transition), render "access denied" instead
  // of showing raw metrics.
  if (!isAdmin) {
    return (
      <div
        role="alert"
        data-testid="usps-direct-dashboard-access-denied"
        className="mx-auto mt-8 max-w-lg rounded-2xl border border-slate-200 bg-white p-6 text-center shadow-sm"
      >
        <FiAlertTriangle className="mx-auto mb-2 h-6 w-6 text-amber-500" />
        <h2 className="text-[15px] font-bold text-slate-800">Access denied</h2>
        <p className="mt-1 text-[12.5px] text-slate-600">
          The USPS Direct dashboard is restricted to administrators.
        </p>
      </div>
    )
  }

  const generatedLabel = snapshot?.generatedAt
    ? formatGenerated(snapshot.generatedAt)
    : 'awaiting first refresh…'

  return (
    <div
      data-testid="usps-direct-dashboard-page"
      className="mx-auto max-w-6xl space-y-4"
    >
      {/* ===== Header row ===== */}
      <header className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <div>
          <h1 className="text-[16px] font-bold text-slate-800">
            USPS Direct Dashboard
          </h1>
          <p
            data-testid="usps-direct-dashboard-generated"
            className="text-[12px] text-slate-500"
          >
            Generated: {generatedLabel}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            data-testid="usps-direct-dashboard-refresh"
            onClick={() => void load()}
            disabled={refreshing}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <FiRefreshCw
              className={`h-3.5 w-3.5 ${refreshing ? 'animate-spin' : ''}`}
              aria-hidden="true"
            />
            Refresh
          </button>
          <button
            type="button"
            data-testid="usps-direct-dashboard-auto-toggle"
            onClick={() => setAutoRefresh((v) => !v)}
            aria-pressed={autoRefresh}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-[12px] font-semibold transition ${
              autoRefresh
                ? 'border-emerald-300 bg-emerald-50 text-emerald-700 hover:bg-emerald-100'
                : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            }`}
          >
            {autoRefresh ? (
              <>
                <FiPlayCircle className="h-3.5 w-3.5" aria-hidden="true" />
                Auto: on
              </>
            ) : (
              <>
                <FiPauseCircle className="h-3.5 w-3.5" aria-hidden="true" />
                Auto: off
              </>
            )}
          </button>
        </div>
      </header>

      {/* Persistent stale-data warning while an error keeps repeating
          but a previous snapshot is still on screen. */}
      {error && snapshot ? (
        <div
          role="alert"
          data-testid="usps-direct-dashboard-stale-warning"
          className="flex items-center gap-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-800"
        >
          <FiAlertTriangle className="h-3.5 w-3.5" aria-hidden="true" />
          Couldn&apos;t refresh — showing last snapshot. {error}
        </div>
      ) : null}

      {/* ===== Top row: Queue + Quota side-by-side on md+, stack on mobile ===== */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <QueueDepthPanel
          metrics={snapshot?.queue ?? null}
          loading={loading}
          error={error}
        />
        <QuotaHeadroomPanel
          quota={snapshot?.quota ?? null}
          loading={loading}
          error={error}
        />
      </div>

      {/* ===== Full-width retry buckets chart ===== */}
      <RetryBucketsPanel
        buckets={snapshot?.retries ?? null}
        loading={loading}
        error={error}
      />

      {/* ===== Reconciliation rollup ===== */}
      <ReconciliationRollupPanel
        rollup={snapshot?.reconciliation ?? null}
        loading={loading}
        error={error}
      />
    </div>
  )
}

/**
 * ISO datetime → local "YYYY-MM-DD HH:MM UTC-offset". Kept compact —
 * the header line is one row, this string is the second-most important
 * datum on the page (after the fact that the page rendered at all).
 */
function formatGenerated(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd} ${hh}:${mi}`
}
