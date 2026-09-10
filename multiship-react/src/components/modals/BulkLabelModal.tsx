import { useEffect, useRef, useState } from 'react'
import {
  FiAlertCircle,
  FiCheckCircle,
  FiDownload,
  FiPackage,
  FiSlash,
  FiX,
  FiZap,
} from 'react-icons/fi'
import { bulkLabelService, type BulkLabelJob } from '../../api/bulkLabelService'
import { notify } from '../../utils/notify'
import { useFocusTrap } from '../../hooks/useFocusTrap'

/**
 * Sprint 37 — bulk label generation modal. Submits the batch, polls
 * status every 2s, and offers a Download link when the ZIP is ready.
 * The whole flow lives in the modal — the parent just supplies the
 * order numbers.
 */
export interface BulkLabelModalProps {
  onClose: () => void
  orderNumbers: number[]
}

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
   * Bulk MED — exponential backoff on the status poll. Prior to this
   * fix the FE polled every 2s regardless of progress, which added up
   * fast: 10 concurrent operators × 1 poll/2s × server-side ZIP-load
   * (until the sibling backend fix landed) = gigabytes/min of pointless
   * traffic. Now the poll starts at 1s (fast feedback on quick jobs),
   * doubles up to a 15s ceiling, and resets whenever an incremental
   * counter advances (real progress → poll faster; nothing happening →
   * back off). The terminal-status branch clears the timer regardless.
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
            // Progress observed — reset backoff so the operator sees
            // fast updates while orders are actively completing.
            delayMs = MIN_DELAY
            lastDone = done
          } else {
            // Steady state — double the interval, capped, so a batch
            // waiting on a slow carrier doesn't hammer /status.
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
        // Transient poll failures don't kill the session — keep polling
        // but back off so a persistently failing endpoint doesn't
        // consume all the browser's connection budget.
        console.warn('Poll error', e)
        delayMs = Math.min(delayMs * 2, MAX_DELAY)
      }
      pollTimer.current = window.setTimeout(tick, delayMs)
    }
    // Fire the first tick immediately so the operator sees status right
    // after Submit rather than waiting 1s for the first setTimeout.
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

      {job.failureMessage ? (
        <div className="rounded-xl border border-rose-200 bg-rose-50/60 px-3 py-2 text-[11.5px] text-rose-800">
          <p className="font-semibold">Failures</p>
          <pre className="mt-1 max-h-40 overflow-y-auto whitespace-pre-wrap text-[10.5px] font-mono">
            {job.failureMessage}
          </pre>
        </div>
      ) : null}
    </div>
  )
}
