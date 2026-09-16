import { useMemo, useState, type ReactNode } from 'react'
import {
  FiAlertTriangle,
  FiCheckCircle,
  FiChevronDown,
  FiChevronUp,
  FiCopy,
  FiPackage,
  FiSlash,
} from 'react-icons/fi'
import { useMpsProgress } from '../../hooks/useMpsProgress'
import { formatQueueDuration } from './uspsQueueFormat'
import { notify } from '../../utils/notify'

/**
 * PR-F2 — compact aggregate-progress card for MPS shipments moving
 * through the USPS Direct queue.
 *
 * <p>Renders:
 * <pre>
 *   +------------------------------------------------------------+
 *   | 📦 MPS shipment · Order #12345                            |
 *   | ▓▓▓▓▓▓▓▓░░░░░░░░  400 of 1000 · 40.0%                     |
 *   | Queued 595 · Processing 5 · Done 400                       |
 *   | Est. complete: Sep 17, 14:32 UTC (in ~45 min)              |
 *   | [Show 20 tracking numbers ▾]                               |
 *   +------------------------------------------------------------+
 * </pre>
 *
 * <p>Uses {@link useMpsProgress} for the polling + backoff; the card
 * itself only renders. That split lets other surfaces (an admin
 * dashboard, an SSE fallback banner) reuse the same fetch loop without
 * copying the timing rules.
 *
 * <p>Empty states:
 * <ul>
 *   <li>{@code orderNo == null} → renders nothing (parent hasn't
 *       resolved the order yet).</li>
 *   <li>404 (hook returns {@code progress = null} + no error) →
 *       "No MPS progress found" fallback with a hint.</li>
 *   <li>API error → red banner with the message.</li>
 * </ul>
 */
export interface MpsProgressCardProps {
  /** Parent MPS order number to watch. Null renders nothing. */
  orderNo: number | null
  /**
   * Pass-through to the hook — override the default 15s cadence.
   * Storybook / tests use this to keep interval short.
   */
  pollIntervalMs?: number
  /**
   * Optional click handler for the "Download all labels" button that
   * appears once {@code percentComplete >= 100}. Parent surfaces
   * usually already own a bulk-label download flow; wire it here so
   * the card stays surface-agnostic. Omit to hide the button entirely.
   */
  onDownloadAllLabels?: () => void
}

export default function MpsProgressCard({
  orderNo,
  pollIntervalMs,
  onDownloadAllLabels,
}: MpsProgressCardProps) {
  const { progress, error, loading } = useMpsProgress(orderNo, {
    pollIntervalMs,
    enabled: orderNo != null,
  })
  const [expanded, setExpanded] = useState(false)

  // The whole card disappears when orderNo is unset (parent didn't
  // know the MPS parent yet). Keeps the JSX in call sites simple —
  // <MpsProgressCard orderNo={maybeNull} /> just works.
  if (orderNo == null) return null

  // ---- Empty / error branches ------------------------------------
  if (error) {
    return (
      <div
        role="alert"
        data-testid="mps-progress-card-error"
        className="rounded-xl border border-rose-200 bg-rose-50/60 px-3 py-2 text-[12px] text-rose-800"
      >
        <div className="flex items-start gap-1.5">
          <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" aria-hidden="true" />
          <div>
            <p className="font-semibold">Couldn&rsquo;t load MPS progress</p>
            <p className="mt-0.5 text-[11.5px]">{error}</p>
          </div>
        </div>
      </div>
    )
  }

  if (progress == null) {
    // Loading before first fetch OR 404 after a fetch. Render the same
    // placeholder either way — the polling loop backs off automatically
    // once 3 consecutive 404s have been seen so this isn't a hot loop.
    return (
      <div
        data-testid="mps-progress-card-empty"
        className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12px] text-slate-600"
      >
        <div className="flex items-start gap-1.5">
          <FiPackage className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-slate-400" aria-hidden="true" />
          <div>
            <p className="font-semibold text-slate-700">
              No MPS progress found
            </p>
            <p className="mt-0.5 text-[11.5px] text-slate-500">
              {loading
                ? 'Checking USPS Direct queue…'
                : `Order #${orderNo} has no USPS Direct queue rows yet — either it isn't an MPS shipment or every child piece has been processed and cleaned up.`}
            </p>
          </div>
        </div>
      </div>
    )
  }

  // ---- Progress card --------------------------------------------
  const {
    parentOrderNo,
    totalPieces,
    byStatus,
    percentComplete,
    estimatedCompletionAt,
    trackingNumbers,
  } = progress

  const done = byStatus.DONE ?? 0
  const queued = byStatus.QUEUED ?? 0
  const processing = byStatus.PROCESSING ?? 0
  const failed = byStatus.FAILED ?? 0
  const cancelled = byStatus.CANCELLED ?? 0
  const isComplete = percentComplete >= 100
  const barColor = isComplete ? 'bg-emerald-500' : 'bg-amber-500'
  const barTrack = isComplete ? 'bg-emerald-100' : 'bg-amber-100'

  return (
    <div
      data-testid="mps-progress-card"
      className="rounded-xl border border-slate-200 bg-white p-3 shadow-sm"
    >
      <div className="flex items-center justify-between gap-2">
        <p className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-slate-800">
          <FiPackage className="h-3.5 w-3.5 text-slate-500" aria-hidden="true" />
          MPS shipment · Order #{parentOrderNo}
        </p>
        {isComplete ? (
          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-0.5 text-[10.5px] font-semibold text-emerald-800">
            <FiCheckCircle className="h-3 w-3" aria-hidden="true" /> Complete
          </span>
        ) : null}
      </div>

      <div className="mt-2">
        <div
          role="progressbar"
          aria-valuenow={Math.round(percentComplete)}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`MPS progress — ${done} of ${totalPieces} labels`}
          data-testid="mps-progress-bar"
          className={`h-2 overflow-hidden rounded-full ${barTrack}`}
        >
          <div
            data-testid="mps-progress-bar-fill"
            className={`h-full transition-all ${barColor}`}
            style={{ width: `${Math.min(100, Math.max(0, percentComplete))}%` }}
          />
        </div>
        <div className="mt-1 flex items-center justify-between text-[11px] text-slate-600">
          <span className="tabular-nums font-semibold text-slate-800">
            {done} of {totalPieces}
          </span>
          <span className="tabular-nums font-mono text-slate-700" data-testid="mps-percent">
            {percentComplete.toFixed(1)}%
          </span>
        </div>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
        <StatusPill label="Queued" count={queued} className="text-slate-700" />
        <StatusPill label="Processing" count={processing} className="text-sky-700" />
        <StatusPill label="Done" count={done} className="text-emerald-700" />
        {failed > 0 ? (
          <StatusPill
            label="Failed"
            count={failed}
            className="text-rose-700"
            icon={<FiAlertTriangle className="h-3 w-3" aria-hidden="true" />}
            testId="mps-failed-pill"
          />
        ) : null}
        {cancelled > 0 ? (
          <StatusPill
            label="Cancelled"
            count={cancelled}
            className="text-slate-500"
            icon={<FiSlash className="h-3 w-3" aria-hidden="true" />}
            testId="mps-cancelled-pill"
          />
        ) : null}
      </div>

      <p className="mt-2 text-[11px] text-slate-500">
        Est. complete:{' '}
        <span className="tabular-nums text-slate-700" data-testid="mps-eta">
          {formatEta(estimatedCompletionAt, isComplete)}
        </span>
      </p>

      {trackingNumbers.length > 0 ? (
        <TrackingNumbersSection
          expanded={expanded}
          onToggle={() => setExpanded((v) => !v)}
          trackingNumbers={trackingNumbers}
        />
      ) : null}

      {isComplete && onDownloadAllLabels ? (
        <div className="mt-3">
          <button
            type="button"
            onClick={onDownloadAllLabels}
            data-testid="mps-download-all"
            className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-emerald-50 px-3 py-1.5 text-[12px] font-semibold text-emerald-800 transition hover:bg-emerald-100"
          >
            <FiCheckCircle className="h-3 w-3" />
            Download all labels
          </button>
        </div>
      ) : null}
    </div>
  )
}

function StatusPill({
  label,
  count,
  className,
  icon,
  testId,
}: {
  label: string
  count: number
  className: string
  icon?: ReactNode
  testId?: string
}) {
  return (
    <span
      className={`inline-flex items-center gap-1 ${className}`}
      data-testid={testId}
    >
      {icon}
      <span className="font-semibold tabular-nums">{count}</span>
      <span className="font-normal text-slate-500">{label}</span>
    </span>
  )
}

function TrackingNumbersSection({
  expanded,
  onToggle,
  trackingNumbers,
}: {
  expanded: boolean
  onToggle: () => void
  trackingNumbers: string[]
}) {
  // Memoize the joined string so the copy handler doesn't reallocate
  // on every render (harmless perf detail — kept because the parent
  // re-renders every poll tick).
  const joined = useMemo(() => trackingNumbers.join('\n'), [trackingNumbers])

  const handleCopyAll = async () => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(joined)
        notify.success(`Copied ${trackingNumbers.length} tracking numbers.`)
      } else {
        // Fallback for jsdom / very old browsers — the modal user
        // shouldn't hit this in prod but the empty catch would otherwise
        // silently swallow the click.
        notify.error('Clipboard API is unavailable in this browser.')
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Copy failed.'
      notify.error(`Copy failed: ${msg}`)
    }
  }

  return (
    <div className="mt-3 border-t border-slate-100 pt-2">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        data-testid="mps-tracking-toggle"
        className="inline-flex items-center gap-1 text-[11.5px] font-semibold text-slate-700 hover:text-slate-900"
      >
        {expanded ? (
          <FiChevronUp className="h-3 w-3" aria-hidden="true" />
        ) : (
          <FiChevronDown className="h-3 w-3" aria-hidden="true" />
        )}
        {expanded ? 'Hide' : 'Show'} {trackingNumbers.length} tracking number
        {trackingNumbers.length === 1 ? '' : 's'}
      </button>
      {expanded ? (
        <div className="mt-2 space-y-2" data-testid="mps-tracking-list">
          <ul className="max-h-40 overflow-y-auto rounded-lg border border-slate-200 bg-slate-50 p-2 font-mono text-[11px] text-slate-800">
            {trackingNumbers.map((tn, i) => (
              <li key={`${tn}-${i}`} className="tabular-nums leading-snug">
                {tn}
              </li>
            ))}
          </ul>
          <button
            type="button"
            onClick={() => void handleCopyAll()}
            data-testid="mps-tracking-copy"
            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiCopy className="h-3 w-3" aria-hidden="true" />
            Copy all
          </button>
        </div>
      ) : null}
    </div>
  )
}

/**
 * Format the est.-complete line. Once the job is done we say so verbatim
 * (there's no future ETA to show). Missing ETA before any DONE row lands
 * returns a "Calculating…" placeholder so operators see the card react
 * to the first response.
 *
 * <p>When the ETA is set we render both the absolute UTC time
 * ("Sep 17, 14:32 UTC") AND the relative "in ~N min" derived from
 * {@link formatQueueDuration} — operators tell us the relative form is
 * more useful at a glance while the absolute one settles disputes about
 * "did this thing stall".
 */
function formatEta(iso: string | null, isComplete: boolean): string {
  if (isComplete) return 'Done'
  if (!iso) return 'Calculating…'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return 'Calculating…'
  const secondsFromNow = Math.max(0, Math.round((date.getTime() - Date.now()) / 1000))
  const absolute = date.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'UTC',
  })
  return `${absolute} UTC (in ${formatQueueDuration(secondsFromNow)})`
}
