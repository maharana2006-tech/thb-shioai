import { useEffect, useState } from 'react'
import { FiActivity } from 'react-icons/fi'
import type { UspsQuotaHeadroom } from '../../../api/uspsLabelQueueService'
import { utilizationColor } from './dashboardFormat'

/**
 * PR-F4 Agent-2 — Quota Headroom panel.
 *
 * <p>Renders the USPS 55/hr token-bucket state:
 * <ul>
 *   <li>Horizontal utilization bar (green &lt; 60%, amber 60–85%,
 *       red &gt; 85% — see {@link utilizationColor}) — the color
 *       transitions match the operator's mental model ("we're about to
 *       run out of quota").</li>
 *   <li>{@code remainingTokens / hourlyCap} big-number display.</li>
 *   <li>Countdown to the next replenish tick; ticks down locally each
 *       second (a 30s page-refresh would otherwise leave the countdown
 *       stale for ~25 seconds after the initial fetch).</li>
 * </ul>
 *
 * <p>The panel is stateless in terms of data fetching — the parent
 * page owns the fetch + passes {@code quota} in. Only the countdown
 * decrements locally, and it resets each time {@code quota} changes
 * (i.e. after every parent refresh).
 */
export interface QuotaHeadroomPanelProps {
  quota: UspsQuotaHeadroom | null
  loading?: boolean
  error?: string | null
}

export default function QuotaHeadroomPanel({
  quota,
  loading = false,
  error = null,
}: QuotaHeadroomPanelProps) {
  const [secondsRemaining, setSecondsRemaining] = useState<number>(
    quota?.nextReplenishInSeconds ?? 0,
  )

  // Reset countdown whenever a fresh quota snapshot arrives. The
  // setState-in-effect is intentional (the "external system" being
  // synced is the parent's fetch cadence — see React docs on syncing
  // to props). Same pattern the useMpsProgress hook uses when resetting
  // stale progress on orderNo change.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- resets local countdown to the freshly-fetched replenish schedule
    setSecondsRemaining(quota?.nextReplenishInSeconds ?? 0)
  }, [quota])

  // Tick down locally each second so the countdown stays smooth even
  // when the page refresh interval is longer than 1s. Clamp at 0 —
  // don't wrap negative; the next parent refresh will re-seed with the
  // real replenish schedule.
  useEffect(() => {
    if (secondsRemaining <= 0) return
    const timer = window.setInterval(() => {
      setSecondsRemaining((prev) => (prev > 0 ? prev - 1 : 0))
    }, 1_000)
    return () => window.clearInterval(timer)
  }, [secondsRemaining])

  const util = quota?.utilizationPercent ?? 0
  const barColor = utilizationColor(util)

  return (
    <section
      aria-label="USPS quota headroom"
      data-testid="quota-headroom-panel"
      className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
    >
      <header className="mb-4 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600"
          >
            <FiActivity className="h-4 w-4" />
          </span>
          <div>
            <h3 className="text-[13px] font-bold uppercase tracking-[0.12em] text-slate-500">
              Quota Headroom
            </h3>
            <p className="text-[11.5px] text-slate-500">
              USPS 55/hr token-bucket · consumed per label call.
            </p>
          </div>
        </div>
      </header>

      {loading && !quota && !error ? (
        <div
          role="status"
          aria-live="polite"
          data-testid="quota-headroom-loading"
          className="animate-pulse space-y-3"
        >
          <div className="h-3 w-full rounded-full bg-slate-100" />
          <div className="h-10 w-32 rounded-md bg-slate-100" />
          <div className="h-4 w-40 rounded-md bg-slate-100" />
        </div>
      ) : error && !quota ? (
        <div
          role="alert"
          data-testid="quota-headroom-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-[12.5px] text-rose-700"
        >
          Couldn&apos;t load quota headroom: {error}
        </div>
      ) : !quota ? (
        <p
          data-testid="quota-headroom-empty"
          className="text-[12.5px] text-slate-500"
        >
          No quota data available.
        </p>
      ) : (
        <>
          <div className="mb-4">
            <div
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(util)}
              aria-label={`Quota utilization ${util.toFixed(1)}%`}
              data-testid="quota-headroom-bar"
              className="h-3 w-full overflow-hidden rounded-full bg-slate-100"
            >
              <div
                className={`h-full rounded-full transition-[width] ${barColor}`}
                style={{ width: `${Math.min(100, Math.max(0, util))}%` }}
              />
            </div>
            <p
              data-testid="quota-headroom-percent"
              className="mt-1 text-[11.5px] font-semibold tabular-nums text-slate-500"
            >
              {util.toFixed(1)}% used
            </p>
          </div>

          <div className="flex items-baseline gap-2">
            <span
              data-testid="quota-headroom-remaining"
              className="text-[36px] font-bold tabular-nums leading-none text-slate-800"
            >
              {quota.remainingTokens.toLocaleString()}
            </span>
            <span className="text-[13px] font-medium text-slate-500">
              / {quota.hourlyCap.toLocaleString()} tokens available
            </span>
          </div>

          <dl className="mt-4 grid grid-cols-2 gap-3 text-[12.5px]">
            <div className="rounded-lg border border-slate-100 bg-slate-50 px-3 py-2">
              <dt className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-slate-500">
                Next replenish
              </dt>
              <dd
                data-testid="quota-headroom-next-replenish"
                className="mt-0.5 text-[13px] font-semibold tabular-nums text-slate-700"
              >
                {formatSeconds(secondsRemaining)}
              </dd>
            </div>
            <div className="rounded-lg border border-slate-100 bg-slate-50 px-3 py-2">
              <dt className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-slate-500">
                Last replenish
              </dt>
              <dd
                data-testid="quota-headroom-last-replenish"
                className="mt-0.5 text-[13px] font-semibold text-slate-700"
                title={quota.lastReplenishAt}
              >
                {formatShortTime(quota.lastReplenishAt)}
              </dd>
            </div>
          </dl>
        </>
      )}
    </section>
  )
}

/**
 * Human-readable seconds ("in 47s" / "in 2m 05s"). Sub-minute stays
 * compact so the operator's eye stays on the number, not the unit.
 * Kept local (not exported) so the file stays Fast-Refresh clean.
 */
function formatSeconds(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return 'imminent'
  if (seconds < 60) return `in ${Math.floor(seconds)}s`
  const mins = Math.floor(seconds / 60)
  const secs = Math.floor(seconds % 60)
  return `in ${mins}m ${String(secs).padStart(2, '0')}s`
}

/**
 * ISO timestamp → local HH:MM. Falls back to the raw string if
 * parsing fails so we never render "Invalid Date" to admins.
 */
function formatShortTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  })
}
