import { FiLayers } from 'react-icons/fi'
import type { UspsLabelQueueMetrics } from '../../../api/uspsLabelQueueService'
import { formatQueueDuration } from '../../orders/uspsQueueFormat'

/**
 * PR-F4 Agent-2 — Queue Depth panel for the USPS Direct admin
 * dashboard.
 *
 * <p>Reads the composite dashboard payload's {@code queue} field
 * (same {@link UspsLabelQueueMetrics} shape as {@code BulkLabelQueueBadge}
 * consumes on the operator surface) and surfaces:
 * <ul>
 *   <li>Total PENDING depth as the headline number.</li>
 *   <li>Currently PROCESSING (RUNNING rows) as a supporting stat.</li>
 *   <li>Estimated tail-of-queue wait (reuses PR-F1's {@code formatQueueDuration}
 *       so the copy stays in sync with the badge).</li>
 *   <li>Top 5 tenants by depth when the backend supplies {@code perTenantDepth}.</li>
 * </ul>
 *
 * <p>Loading + error branches are handled at the page level; this
 * component receives the payload (or {@code null} for "empty state").
 * A {@code loading} prop is exposed so the page can show a skeleton
 * without hiding the panel header (keeps layout stable across refreshes).
 */
export interface QueueDepthPanelProps {
  /** Composite dashboard's {@code queue} slice. Null → empty state. */
  metrics: UspsLabelQueueMetrics | null
  /** True while the page-level fetch is in flight. Renders a skeleton
   *  when true AND {@code metrics} is null. */
  loading?: boolean
  /** Error message from the page-level fetch. When set + no metrics
   *  yet, renders the error branch. */
  error?: string | null
}

const MAX_TENANT_ROWS = 5

export default function QueueDepthPanel({
  metrics,
  loading = false,
  error = null,
}: QueueDepthPanelProps) {
  const topTenants = deriveTopTenants(metrics?.perTenantDepth ?? null)

  return (
    <section
      aria-label="USPS Direct queue depth"
      data-testid="queue-depth-panel"
      className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
    >
      <header className="mb-4 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600"
          >
            <FiLayers className="h-4 w-4" />
          </span>
          <div>
            <h3 className="text-[13px] font-bold uppercase tracking-[0.12em] text-slate-500">
              Queue Depth
            </h3>
            <p className="text-[11.5px] text-slate-500">
              Pending USPS Direct label requests, waiting to hit USPS.
            </p>
          </div>
        </div>
      </header>

      {loading && !metrics && !error ? (
        <div
          role="status"
          aria-live="polite"
          data-testid="queue-depth-loading"
          className="animate-pulse space-y-3"
        >
          <div className="h-10 w-24 rounded-md bg-slate-100" />
          <div className="h-4 w-40 rounded-md bg-slate-100" />
          <div className="h-24 rounded-md bg-slate-100" />
        </div>
      ) : error && !metrics ? (
        <div
          role="alert"
          data-testid="queue-depth-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-[12.5px] text-rose-700"
        >
          Couldn&apos;t load queue depth: {error}
        </div>
      ) : !metrics ? (
        <p
          data-testid="queue-depth-empty"
          className="text-[12.5px] text-slate-500"
        >
          No queue metrics available.
        </p>
      ) : (
        <>
          <div className="flex items-baseline gap-3">
            <span
              data-testid="queue-depth-count"
              className="text-[36px] font-bold tabular-nums leading-none text-slate-800"
            >
              {metrics.depth.toLocaleString()}
            </span>
            <span className="text-[13px] font-medium text-slate-500">
              item{metrics.depth === 1 ? '' : 's'} pending
            </span>
          </div>

          <dl className="mt-4 grid grid-cols-2 gap-3 text-[12.5px]">
            <div className="rounded-lg border border-slate-100 bg-slate-50 px-3 py-2">
              <dt className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-slate-500">
                Processing
              </dt>
              <dd
                data-testid="queue-depth-processing"
                className="mt-0.5 text-[16px] font-semibold tabular-nums text-slate-700"
              >
                {metrics.processing.toLocaleString()}
              </dd>
            </div>
            <div className="rounded-lg border border-slate-100 bg-slate-50 px-3 py-2">
              <dt className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-slate-500">
                Est. wait
              </dt>
              <dd
                data-testid="queue-depth-wait"
                className="mt-0.5 text-[13px] font-semibold text-slate-700"
              >
                {formatQueueDuration(metrics.estimatedWaitSeconds)}
              </dd>
            </div>
          </dl>

          <div className="mt-4">
            <p className="mb-2 text-[10.5px] font-bold uppercase tracking-[0.12em] text-slate-500">
              Top tenants by depth
            </p>
            {topTenants.length === 0 ? (
              <p
                data-testid="queue-depth-tenants-empty"
                className="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-[12px] text-slate-500"
              >
                {metrics.depth > 0
                  ? 'Per-tenant breakdown unavailable.'
                  : 'No pending requests.'}
              </p>
            ) : (
              <ul
                data-testid="queue-depth-tenants"
                className="divide-y divide-slate-100 overflow-hidden rounded-lg border border-slate-100"
              >
                {topTenants.map(([tenant, depth]) => (
                  <li
                    key={tenant}
                    className="flex items-center justify-between px-3 py-1.5 text-[12.5px]"
                  >
                    <span className="font-mono text-slate-700">{tenant}</span>
                    <span className="tabular-nums font-semibold text-slate-800">
                      {depth.toLocaleString()}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </section>
  )
}

/**
 * Sort the tenant → depth map descending and keep the top
 * {@link #MAX_TENANT_ROWS}. Returns an empty array when the map is
 * missing, empty, or every value is 0. Zero-depth tenants are filtered
 * out so the ranking doesn't show tenants that finished processing.
 */
function deriveTopTenants(
  perTenantDepth: Record<string, number> | null,
): Array<[string, number]> {
  if (!perTenantDepth) return []
  const entries = Object.entries(perTenantDepth)
    .filter(([, depth]) => depth > 0)
    .sort((a, b) => b[1] - a[1])
  return entries.slice(0, MAX_TENANT_ROWS)
}
