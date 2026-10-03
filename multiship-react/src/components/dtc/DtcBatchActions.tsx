import { useEffect, useState, type CSSProperties } from 'react'
import { FiEye, FiMoreVertical, FiPrinter, FiRotateCcw, FiZap } from 'react-icons/fi'
import type { DtcBatchStats } from '../../api/dtcService'
import { useDismissable } from '../../hooks/useDismissable'

/**
 * One batch row's actions, ShipX-style: a Details icon, Print (Reprint once the
 * batch has been printed), and a ⋮ menu holding Generate (pending lines) and
 * Regenerate (lines whose label failed). One run handles both kinds, and the
 * confirmation that follows lists exactly what will be bought.
 */
export default function DtcBatchActions({ batch: b, printedAt, progress, busy, onDetails, onPrint, onGenerate }: {
  batch: DtcBatchStats
  /** When any label of the batch was last printed (ISO) — Print becomes Reprint. */
  printedAt?: string | null
  /** A run in progress for this batch. */
  progress?: { processed: number; total: number } | null
  /** Generate was clicked and the server hasn't answered yet. */
  busy: boolean
  onDetails: () => void
  onPrint: () => void
  onGenerate: () => void
}) {
  const [open, setOpen] = useState(false)
  // The menu floats (position: fixed) so the table's scroll area can't clip it;
  // it opens upward when the row sits near the bottom of the window.
  const [place, setPlace] = useState<CSSProperties>({})
  const ref = useDismissable(open, () => setOpen(false))
  useEffect(() => {
    if (!open) return
    const close = () => setOpen(false)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [open])
  const toggle = (e: React.MouseEvent<HTMLButtonElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    const right = window.innerWidth - r.right
    setPlace(window.innerHeight - r.bottom < 140
      ? { position: 'fixed', right, bottom: window.innerHeight - r.top + 6 }
      : { position: 'fixed', right, top: r.bottom + 6 })
    setOpen((v) => !v)
  }
  const nothingToPrint = b.generatedCount === 0
  const running = !!progress
  const items = [
    { key: 'generate', label: 'Generate', hint: b.pendingCount ? `${b.pendingCount} line${b.pendingCount === 1 ? '' : 's'} not labelled yet` : 'Every line has been tried',
      icon: <FiZap className="h-3.5 w-3.5" />, enabled: b.pendingCount > 0 },
    { key: 'regenerate', label: 'Regenerate', hint: b.failedCount ? `Retry ${b.failedCount} failed line${b.failedCount === 1 ? '' : 's'}` : 'No failed lines',
      icon: <FiRotateCcw className="h-3.5 w-3.5" />, enabled: b.failedCount > 0 },
  ]
  const when = printedAt ? new Date(printedAt) : null

  return (
    <div className="flex items-center gap-1.5">
      <button
        type="button"
        onClick={onDetails}
        aria-label={`Details of batch ${b.batchId}`}
        title="Shipment lines of this batch"
        className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
      >
        <FiEye className="h-3.5 w-3.5" />
      </button>

      <button
        type="button"
        onClick={onPrint}
        disabled={nothingToPrint}
        title={nothingToPrint ? 'Generate labels first — nothing to print yet'
          : when ? `Printed ${when.toLocaleString()} — print the ${b.generatedCount} label${b.generatedCount === 1 ? '' : 's'} again`
            : `Download ${b.generatedCount} label PDF${b.generatedCount === 1 ? '' : 's'} as a ZIP`}
        className={`inline-flex h-7 min-w-[4.75rem] items-center justify-center gap-1 rounded-lg px-2.5 text-[11px] font-semibold shadow-sm transition disabled:cursor-not-allowed disabled:opacity-40 ${
          when ? 'border border-[#cdbf9f] bg-[#f4eede] text-[#412d15] hover:bg-[#ece2cb]' : 'bg-[#1f150c] text-[#f4eede] hover:bg-[#412d15]'
        }`}
      >
        <FiPrinter className="h-3 w-3" />
        {when ? 'Reprint' : 'Print'}
      </button>

      <div ref={ref} className="relative">
        {running ? (
          <span className="inline-flex h-7 items-center gap-1 rounded-lg border border-amber-200 bg-amber-50 px-2 text-[11px] font-semibold text-amber-800" title="Automatic label run in progress">
            <FiZap className="h-3 w-3 animate-pulse" />
            {progress!.processed}/{progress!.total}
          </span>
        ) : (
          <button
            type="button"
            onClick={toggle}
            disabled={busy}
            aria-haspopup="menu"
            aria-expanded={open}
            aria-label={`More actions for batch ${b.batchId}`}
            className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy
              ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#e3d9c4] border-t-[#5a4526]" />
              : <FiMoreVertical className="h-3.5 w-3.5" />}
          </button>
        )}
        {open && !running ? (
          <div role="menu" aria-label={`Batch ${b.batchId}`} style={place} className="bulk-pop-in z-50 w-56 rounded-xl border border-[#e3d9c4] bg-white p-1 shadow-[0_12px_32px_rgba(31,21,12,0.14)]">
            {items.map((it) => (
              <button
                key={it.key}
                type="button"
                role="menuitem"
                disabled={!it.enabled}
                onClick={() => { setOpen(false); onGenerate() }}
                className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
              >
                <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-[#f4eede] text-[#412d15]" aria-hidden="true">{it.icon}</span>
                <span className="min-w-0">
                  <span className="block text-[12px] font-semibold leading-tight text-[#1f150c]">{it.label}</span>
                  <span className="block text-[10.5px] leading-tight text-[#a1906d]">{it.hint}</span>
                </span>
              </button>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  )
}
