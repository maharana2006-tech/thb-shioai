import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  FiAlertCircle,
  FiAlertTriangle,
  FiCheckCircle,
  FiDownload,
  FiPackage,
  FiSlash,
  FiX,
  FiZap,
} from 'react-icons/fi'
import {
  bulkLabelService,
  parseFailureDetails,
  type BulkLabelFailureDetail,
  type BulkLabelJob,
} from '../../api/bulkLabelService'
import {
  uspsLabelQueueService,
  type UspsLabelQueueMetrics,
} from '../../api/uspsLabelQueueService'
import { isAbortError } from '../../api/apiClient'
import BulkLabelQueueBadge from '../orders/BulkLabelQueueBadge'
import MpsProgressCard from '../orders/MpsProgressCard'
import { formatQueueDuration } from '../orders/uspsQueueFormat'
import { notify } from '../../utils/notify'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import { useEventStream } from '../../hooks/useEventStream'

/**
 * Sprint 37 — bulk label generation modal. Submits the batch, polls
 * status every 2s, and offers a Download link when the ZIP is ready.
 * The whole flow lives in the modal — the parent just supplies the
 * order numbers.
 *
 * <p>PR-F1 (USPS_DIRECT scale-hardening) — surfaces the
 * {@link BulkLabelQueueBadge} at the top of the modal, plus a warning
 * banner when queue depth &gt; 50 OR estimated wait &gt; 1h so the
 * operator sees rate-limit pressure BEFORE hitting Start. Both are
 * self-hiding when USPS_PROVIDER is not USPS_DIRECT (no queue rows
 * exist → depth === 0 → nothing renders).
 *
 * <p>PR-F2 (this PR) — when the submitted batch is a single order,
 * surface {@link MpsProgressCard} above the aggregate progress block.
 * "ONE order with 1000 pieces MPS" (docs/usps-direct-integration.md
 * §MPS) is the scenario where the bulk-label counters ("1/1") give the
 * operator zero useful signal; the MPS card polls the aggregate-
 * progress endpoint and shows "400 of 1000 · 40.0%" until the child
 * pieces settle. The card self-hides via its own 404 branch when the
 * order isn't an MPS, so the change is a no-op for single-piece orders.
 */
export interface BulkLabelModalProps {
  onClose: () => void
  orderNumbers: number[]
}

/** Warning threshold: queue tail longer than this seconds surfaces a
 *  "USPS is rate-limited to 60 labels/hour" banner. 3600 = 1h. */
const QUEUE_WARN_WAIT_SEC = 3600
/** Warning threshold: raw depth crossing this triggers the same banner
 *  even if the estimated wait is under an hour (backlog visible before
 *  the rate limiter fully kicks in). */
const QUEUE_WARN_DEPTH = 50

export default function BulkLabelModal({ onClose, orderNumbers }: BulkLabelModalProps) {
  const [job, setJob] = useState<BulkLabelJob | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const pollTimer = useRef<number | null>(null)
  // Sprint 49 Tier 4 Fix 6 — trap Tab focus inside the modal and
  // restore to the trigger on close.
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, dialogRef)

  const downloadZip = async (jobId: number) => {
    if (downloading) return
    setDownloading(true)
    try {
      await bulkLabelService.download(jobId, `bulk-labels-${jobId}.zip`)
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Bulk-label ZIP download failed.'
      setError(msg)
      notify.apiError(e, 'Bulk-label ZIP download failed.')
    } finally {
      setDownloading(false)
    }
  }

  const clearPoll = () => {
    if (pollTimer.current != null) {
      // Bulk MED — the poll loop switched from setInterval to a
      // self-rescheduling setTimeout for exponential backoff, so use
      // clearTimeout here (clearInterval is a no-op on setTimeout IDs).
      window.clearTimeout(pollTimer.current)
      pollTimer.current = null
    }
  }

  const submit = async () => {
    if (submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const response = await bulkLabelService.submit(orderNumbers)
      const created = response.data
      if (!created) throw new Error(response.message ?? 'Submit failed.')
      setJob(created)
      startPolling(created.id)
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Bulk-label submit failed.'
      setError(msg)
      notify.apiError(e, 'Bulk-label submit failed.')
    } finally {
      setSubmitting(false)
    }
  }

  /**
   * Refresh the modal's job state from the server. Extracted so both
   * the SSE handler (push path) and the polling fallback can invoke
   * it without duplication. Handles the terminal-status toast + poll
   * teardown so any code path that lands the final state gets the
   * same UX.
   */
  const refreshJob = useCallback(async (jobId: number) => {
    try {
      const response = await bulkLabelService.status(jobId)
      const next = response.data
      if (!next) return
      setJob(next)
      if (next.status === 'COMPLETED' || next.status === 'FAILED' || next.status === 'CANCELLED') {
        clearPoll()
        if (next.status === 'COMPLETED') {
          notify.success(
            `Bulk labels done — ${next.successfulCount}/${next.totalCount} generated.`,
          )
        } else if (next.status === 'CANCELLED') {
          notify.info(
            `Bulk-label job cancelled — ${next.successfulCount} label(s) finished before the stop.`,
          )
        } else {
          notify.error('Bulk-label job failed. See error message.')
        }
      }
    } catch (e) {
      console.warn('Bulk-label refresh error', e)
    }
  }, [])

  /**
   * Bulk MED — exponential backoff on the status poll. Prior to this
   * fix the FE polled every 2s regardless of progress. Now the poll
   * starts at 1s (fast feedback on quick jobs), doubles up to a 15s
   * ceiling, and resets whenever an incremental counter advances.
   * Terminal-status branch clears the timer.
   *
   * <p>Phase 3 SSE — this is now a FALLBACK. When SSE is connected
   * (see useEventStream below), the polling loop stays dormant and
   * push events drive the UI. If SSE fails or Redis is off, the
   * polling loop takes over transparently.
   */
  const startPolling = (jobId: number) => {
    clearPoll()
    let delayMs = 1_000
    const MIN_DELAY = 1_000
    const MAX_DELAY = 15_000
    let lastDone = -1
    const tick = async () => {
      try {
        const response = await bulkLabelService.status(jobId)
        const next = response.data
        if (next) {
          setJob(next)
          const done = next.successfulCount + next.failedCount
          if (done !== lastDone) {
            delayMs = MIN_DELAY
            lastDone = done
          } else {
            delayMs = Math.min(delayMs * 2, MAX_DELAY)
          }
          if (next.status === 'COMPLETED' || next.status === 'FAILED' || next.status === 'CANCELLED') {
            clearPoll()
            if (next.status === 'COMPLETED') {
              notify.success(
                `Bulk labels done — ${next.successfulCount}/${next.totalCount} generated.`,
              )
            } else if (next.status === 'CANCELLED') {
              notify.info(
                `Bulk-label job cancelled — ${next.successfulCount} label(s) finished before the stop.`,
              )
            } else {
              notify.error('Bulk-label job failed. See error message.')
            }
            return
          }
        }
      } catch (e) {
        console.warn('Poll error', e)
        delayMs = Math.min(delayMs * 2, MAX_DELAY)
      }
      pollTimer.current = window.setTimeout(tick, delayMs)
    }
    void tick()
  }

  /**
   * Ask the backend to cancel the in-flight run. Already-in-flight
   * carrier calls run to completion (server-side); UI immediately
   * disables the cancel button and waits for the poll to bring back
   * status=CANCELLED. Confirm dialog protects against accidental clicks.
   */
  const cancelJob = async () => {
    if (!job || cancelling) return
    const ok = window.confirm(
      `Cancel bulk-label job ${job.id}? Workers stop after the current in-flight orders finish. ` +
        `Labels already generated stay downloadable.`,
    )
    if (!ok) return
    setCancelling(true)
    try {
      const response = await bulkLabelService.cancel(job.id)
      if (response.data) setJob(response.data)
      notify.info('Cancellation requested. Waiting for workers to drain…')
    } catch (e) {
      // 409 = already terminal (raced our own poll). Surface friendly text.
      const msg = e instanceof Error ? e.message : 'Cancel failed.'
      setError(msg)
      notify.apiError(e, 'Cancel failed.')
    } finally {
      setCancelling(false)
    }
  }

  useEffect(() => {
    return () => clearPoll()
  }, [])

  /**
   * Phase 3 — real-time SSE push subscription. Opens while a job is
   * active and swaps the polling fallback for a single long-lived
   * connection that receives job-updated events as the backend
   * publishes them. When SSE is 'open' we stop the poll to avoid
   * doubling up requests; when it drops (network hiccup, Redis off,
   * proxy strips text/event-stream), polling automatically resumes
   * so the operator never sees stale state.
   *
   * Only subscribed while THIS job is running — a completed job's
   * modal doesn't need a live connection.
   */
  const activeJobId = job && (job.status === 'PENDING' || job.status === 'RUNNING') ? job.id : null
  const sseHandlers = useMemo(() => ({
    'job-created': (payload: unknown) => {
      const p = payload as { jobId?: number }
      if (p?.jobId === activeJobId) void refreshJob(activeJobId)
    },
    'job-updated': (payload: unknown) => {
      const p = payload as { jobId?: number }
      if (p?.jobId === activeJobId) void refreshJob(activeJobId)
    },
    'job-cancel-requested': (payload: unknown) => {
      const p = payload as { jobId?: number }
      if (p?.jobId === activeJobId) void refreshJob(activeJobId)
    },
  }), [activeJobId, refreshJob])

  const { status: sseStatus } = useEventStream({
    enabled: activeJobId != null,
    topics: ['bulk-labels'],
    handlers: sseHandlers,
  })

  // When SSE flips to 'open', silence the polling fallback (push is
  // authoritative). When it drops back to 'error' / 'connecting', let
  // the poll restart. Only touches the timer — the connection lifecycle
  // stays with useEventStream.
  useEffect(() => {
    if (!activeJobId) return
    if (sseStatus === 'open') {
      clearPoll()
    } else if (pollTimer.current == null) {
      startPolling(activeJobId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- clearPoll + startPolling are stable helpers over refs; only SSE status + job id should toggle the poll on/off
  }, [sseStatus, activeJobId])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const progress = job
    ? job.totalCount > 0
      ? Math.round(((job.successfulCount + job.failedCount) / job.totalCount) * 100)
      : 0
    : 0

  /**
   * PR-F2 — the MPS-progress card polls the aggregate-progress
   * endpoint for one parent order. We derive the candidate parent
   * order number here:
   *
   *   - Single-order submissions ({@code orderNumbers.length === 1}):
   *     that order IS the candidate. If it's an MPS parent the card
   *     shows aggregate progress; if not, the card's 404 branch
   *     hides it. This covers the "ONE order with 1000 pieces MPS"
   *     scenario docs/usps-direct-integration.md calls out.
   *   - Multi-order submissions: skip. We don't currently get per-
   *     row parent_order_no back from the submit response, and
   *     mounting N cards would be noise. A future PR could add a
   *     per-row parent_order_no on the wire and re-enable this for
   *     mixed batches.
   */
  const candidateMpsParentOrderNo = orderNumbers.length === 1 ? orderNumbers[0] : null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Bulk label generation"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
      onKeyDown={(e) => { if (e.key === 'Escape') onClose() }}
    >
      <div
        ref={dialogRef}
        className="flex h-[min(600px,90vh)] w-full max-w-[560px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-500">
              <FiPackage className="h-3 w-3" /> Bulk labels
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Generate {orderNumbers.length} labels
            </h3>
            <p className="mt-1 text-[11.5px] text-slate-500">
              Runs in the background — you can close this modal and come back later to download.
            </p>
            {/* PR-F1 — USPS queue depth pill. Self-hides when the
                queue is empty (i.e. USPS_PROVIDER != USPS_DIRECT, or
                USPS_DIRECT with no backlog). Refreshes every 30s
                on its own. */}
            <div className="mt-2">
              <BulkLabelQueueBadge />
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
          >
            <FiX className="h-3.5 w-3.5" />
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
          {/* PR-F1 — rate-limit warning banner. Only appears when the
              queue depth crosses a threshold OR the wait is over an
              hour. Silent when USPS_PROVIDER != USPS_DIRECT because
              the queue has no rows in those states. */}
          <UspsQueueWarningBanner />

          {/* PR-F2 — MPS aggregate-progress card. Only mounted after a
              submit (job != null) AND for single-order batches. The
              card self-hides on 404, so single-piece orders show
              nothing here. */}
          {job && candidateMpsParentOrderNo != null ? (
            <MpsProgressCard
              orderNo={candidateMpsParentOrderNo}
              onDownloadAllLabels={
                job.downloadable ? () => void downloadZip(job.id) : undefined
              }
            />
          ) : null}

          {!job ? (
            <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-3 text-[12.5px] text-slate-700">
              <p className="font-semibold">Ready to submit</p>
              <p className="mt-1">
                {orderNumbers.length} orders will run in parallel (bounded so carriers aren&rsquo;t rate-limited).
                Successful labels will be zipped for download; failed orders will show inline.
              </p>
            </div>
          ) : (
            <ProgressBlock job={job} progress={progress} />
          )}

          {error ? (
            <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
              <FiAlertCircle className="mr-1.5 inline h-3.5 w-3.5" />
              {error}
            </div>
          ) : null}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3">
          {job?.downloadable ? (
            <button
              type="button"
              onClick={() => void downloadZip(job.id)}
              disabled={downloading}
              className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-emerald-50 px-3 py-1.5 text-[12px] font-semibold text-emerald-800 hover:bg-emerald-100 disabled:opacity-40"
            >
              <FiDownload className="h-3 w-3" />
              {downloading ? 'Downloading…' : `Download ${job.successfulCount} labels (zip)`}
            </button>
          ) : null}
          {job && (job.status === 'PENDING' || job.status === 'RUNNING') ? (
            <button
              type="button"
              onClick={() => void cancelJob()}
              disabled={cancelling}
              className="inline-flex items-center gap-1.5 rounded-lg border border-rose-300 bg-rose-50 px-3 py-1.5 text-[12px] font-semibold text-rose-800 transition hover:bg-rose-100 disabled:opacity-40"
              title="Stop workers from picking up new orders. In-flight carrier calls run to completion."
            >
              <FiSlash className="h-3 w-3" />
              {cancelling ? 'Cancelling…' : 'Cancel job'}
            </button>
          ) : null}
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50"
          >
            Close
          </button>
          {!job ? (
            <button
              type="button"
              onClick={() => void submit()}
              disabled={submitting || orderNumbers.length === 0}
              className="inline-flex items-center gap-1.5 rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40"
            >
              <FiZap className="h-3 w-3" />
              {submitting ? 'Submitting…' : 'Start bulk job'}
            </button>
          ) : null}
        </div>
      </div>
    </div>
  )
}

/**
 * PR-F1 — rate-limit banner. Owns its own metrics fetch (kept
 * separate from {@link BulkLabelQueueBadge} so each component has one
 * job); refreshes on the same 30s cadence. Renders nothing on the
 * empty-queue path so this is a no-op for STAMPS_COM operators.
 *
 * <p>The "60 labels/hour" copy references USPS's platform-wide cap;
 * the actual pace-limit is 55/hr (safety margin — see
 * {@code docs/usps-direct-integration.md} section 11). We surface the
 * ceiling operators actually care about, not the internal margin.
 */
function UspsQueueWarningBanner() {
  const [metrics, setMetrics] = useState<UspsLabelQueueMetrics | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const resp = await uspsLabelQueueService.getMetrics()
        if (cancelled) return
        setMetrics(resp?.data ?? null)
      } catch (err) {
        if (isAbortError(err)) return
        console.debug('UspsQueueWarningBanner fetch failed', err)
        if (!cancelled) setMetrics(null)
      }
    }
    void load()
    const timer = window.setInterval(() => void load(), 30_000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  if (metrics == null) return null
  const shouldWarn =
    metrics.depth > QUEUE_WARN_DEPTH
    || metrics.estimatedWaitSeconds > QUEUE_WARN_WAIT_SEC
  if (!shouldWarn) return null

  const perHourCap = metrics.totalPerHourCap ?? 60
  return (
    <div
      role="alert"
      data-testid="usps-queue-warning"
      className="rounded-xl border border-amber-300 bg-amber-50 px-3 py-2 text-[12px] text-amber-900"
    >
      <div className="flex items-start gap-1.5">
        <FiAlertTriangle
          className="mt-0.5 h-3.5 w-3.5 flex-shrink-0"
          aria-hidden="true"
        />
        <div>
          <p className="font-semibold">
            USPS is rate-limited to {perHourCap} labels/hour.
          </p>
          <p className="mt-0.5">
            {metrics.depth} label{metrics.depth === 1 ? '' : 's'} already queued;
            new USPS orders in this batch will start printing in about{' '}
            <span className="tabular-nums font-semibold">
              {formatQueueDuration(metrics.estimatedWaitSeconds)}
            </span>
            . FedEx / UPS / DHL orders skip the queue.
          </p>
        </div>
      </div>
    </div>
  )
}

function ProgressBlock({ job, progress }: { job: BulkLabelJob; progress: number }) {
  const done = job.successfulCount + job.failedCount
  const isCancelled = job.status === 'CANCELLED'
  const isDone = job.status === 'COMPLETED' || job.status === 'FAILED' || isCancelled
  const barColor = isCancelled
    ? 'bg-amber-500'
    : isDone
      ? 'bg-emerald-500'
      : 'bg-slate-950'
  const badgeColor = isCancelled
    ? 'text-amber-800'
    : 'text-emerald-800'
  return (
    <div className="space-y-3">
      <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-3">
        <div className="flex items-center justify-between gap-2 text-[11.5px] font-semibold text-slate-700">
          <span>
            {isDone ? (
              <span className={`inline-flex items-center gap-1.5 ${badgeColor}`}>
                <FiCheckCircle className="h-3.5 w-3.5" /> {job.status}
              </span>
            ) : (
              <>Running… {done} of {job.totalCount} done</>
            )}
          </span>
          <span className="font-mono">{progress}%</span>
        </div>
        <div className="mt-2 h-2 overflow-hidden rounded-full bg-slate-200">
          <div
            className={`h-full transition-all ${barColor}`}
            style={{ width: `${progress}%` }}
          />
        </div>
        <div className="mt-3 flex items-center justify-between gap-2 text-[11px]">
          <span className="rounded-full bg-emerald-100 px-2 py-0.5 font-semibold text-emerald-800">
            ✓ {job.successfulCount} successful
          </span>
          <span className="rounded-full bg-rose-100 px-2 py-0.5 font-semibold text-rose-800">
            ✗ {job.failedCount} failed
          </span>
          <span className="rounded-full bg-slate-200 px-2 py-0.5 font-semibold text-slate-600">
            {job.totalCount} total
          </span>
        </div>
      </div>

      {job.failureMessage || job.failureDetailsJson ? (
        <FailureBlock job={job} />
      ) : null}
    </div>
  )
}

/**
 * Bulk MED — render structured per-order failures as a proper table
 * when the backend provided failureDetailsJson (V51 and later). Falls
 * back to the legacy failureMessage text blob when structured is
 * absent (jobs from before V51, or all-failed-globally rows).
 */
function FailureBlock({ job }: { job: BulkLabelJob }) {
  const details = parseFailureDetails(job.failureDetailsJson)
  if (details.length === 0) {
    // Legacy fallback — old jobs OR global-failure-only jobs where
    // the parser found no structured rows.
    return (
      <div className="rounded-xl border border-rose-200 bg-rose-50/60 px-3 py-2 text-[11.5px] text-rose-800">
        <p className="font-semibold">Failures</p>
        <pre className="mt-1 max-h-40 overflow-y-auto whitespace-pre-wrap text-[10.5px] font-mono">
          {job.failureMessage}
        </pre>
      </div>
    )
  }
  return (
    <div className="rounded-xl border border-rose-200 bg-rose-50/60 px-3 py-2 text-[11.5px] text-rose-800">
      <p className="font-semibold">
        Details ({details.length} {details.length === 1 ? 'entry' : 'entries'})
      </p>
      <div className="mt-1 max-h-48 overflow-y-auto">
        <table className="w-full text-left text-[10.5px]">
          <thead>
            <tr className="border-b border-rose-200 text-[10px] uppercase tracking-wide text-rose-700/80">
              <th className="py-1 pr-2 font-semibold">Order</th>
              <th className="py-1 pr-2 font-semibold">Code</th>
              <th className="py-1 font-semibold">Reason</th>
            </tr>
          </thead>
          <tbody>
            {details.map((d, i) => (
              <FailureRow key={i} detail={d} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function FailureRow({ detail }: { detail: BulkLabelFailureDetail }) {
  // Slightly different chip color per code so operators can eyeball
  // patterns (all AUTH_REJECTED → creds problem; all RATE_LIMITED →
  // slow down; mixed → real per-order data issues).
  const codeChipClass = codeChip(detail.code)
  return (
    <tr className="border-b border-rose-100 last:border-0 align-top">
      <td className="py-1 pr-2 font-mono tabular-nums">
        {detail.orderNo === 0 ? '—' : detail.orderNo}
      </td>
      <td className="py-1 pr-2">
        <span className={`inline-block rounded-full px-1.5 py-0.5 text-[9.5px] font-semibold ${codeChipClass}`}>
          {detail.code}
        </span>
      </td>
      <td className="py-1 text-rose-900">{detail.message}</td>
    </tr>
  )
}

function codeChip(code: string): string {
  switch (code) {
    case 'ALREADY_LABELED':
      return 'bg-emerald-100 text-emerald-800'   // not really a failure — info
    case 'USPS_QUEUED':
      return 'bg-amber-100 text-amber-800'       // PR-F1 — routed to the queue
    case 'CANCELLED':
      return 'bg-amber-100 text-amber-800'
    case 'RATE_LIMITED':
      return 'bg-orange-100 text-orange-800'
    case 'AUTH_REJECTED':
    case 'NO_CREDENTIALS':
      return 'bg-rose-200 text-rose-900'         // needs operator action
    case 'NETWORK':
    case 'LABEL_FETCH_FAILED':
      return 'bg-slate-200 text-slate-800'       // usually transient
    case 'VALIDATION':
      return 'bg-yellow-100 text-yellow-800'     // operator fixable per-order
    case 'WORKER_FAILURE':
    case 'GLOBAL_FAILURE':
      return 'bg-purple-100 text-purple-800'     // platform bug, escalate
    default:
      return 'bg-rose-100 text-rose-800'         // generic CARRIER_FAILURE / UNKNOWN
  }
}
