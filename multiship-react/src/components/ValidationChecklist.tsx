import { useState } from 'react'
import { FiAlertTriangle, FiCheckCircle, FiChevronDown, FiMinusCircle, FiRefreshCw, FiX, FiXCircle } from 'react-icons/fi'
import type { ReactNode } from 'react'
import type { ShipmentValidationResult } from '../api/shipmentValidationService'
import { buildCheckGroups, type CheckGroup, type CheckGroupKey } from '../utils/validationChecklist'
import ValidationQuote from './ValidationQuote'

const TONE: Record<CheckGroup['status'], { icon: ReactNode; chip: string; label: (g: CheckGroup) => string }> = {
  fail: {
    icon: <FiXCircle className="h-4 w-4 text-rose-600" />,
    chip: 'bg-rose-50 text-rose-700 ring-rose-200',
    label: (g) => `${g.errors.length} to fix`,
  },
  warn: {
    icon: <FiAlertTriangle className="h-4 w-4 text-amber-600" />,
    chip: 'bg-amber-50 text-amber-800 ring-amber-200',
    label: (g) => `${g.warnings.length} to review`,
  },
  pass: {
    icon: <FiCheckCircle className="h-4 w-4 text-emerald-600" />,
    chip: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    label: () => 'Passed',
  },
  skipped: {
    icon: <FiMinusCircle className="h-4 w-4 text-slate-400" />,
    chip: 'bg-slate-50 text-slate-500 ring-slate-200',
    label: () => 'Not checked',
  },
}

const goTo = (sectionId: string) =>
  document.getElementById(sectionId)?.scrollIntoView({ behavior: 'smooth', block: 'start' })

/**
 * The Validate result, as a checklist beside the buttons: one row per area
 * (addresses, package, service & price, customs, carrier), each passed /
 * to review / to fix / not checked. Rows with something to fix or review
 * start open.
 */
export default function ValidationChecklist({
  result,
  checkedAt,
  stale,
  busy,
  onRevalidate,
  onClose,
}: {
  result: ShipmentValidationResult
  checkedAt: Date | null
  /** The form changed after this check ran. */
  stale: boolean
  busy: boolean
  onRevalidate: () => void
  onClose: () => void
}) {
  const groups = buildCheckGroups(result)
  const [open, setOpen] = useState<Set<CheckGroupKey>>(
    () => new Set(groups.filter((g) => g.status === 'fail' || g.status === 'warn').map((g) => g.key)),
  )
  const toggle = (k: CheckGroupKey) => setOpen((s) => {
    const n = new Set(s)
    if (n.has(k)) n.delete(k)
    else n.add(k)
    return n
  })
  const headline = result.overall === 'PASS'
    ? { tone: 'text-emerald-800', icon: <FiCheckCircle className="h-4 w-4" /> }
    : result.overall === 'WARN'
      ? { tone: 'text-amber-800', icon: <FiAlertTriangle className="h-4 w-4" /> }
      : { tone: 'text-rose-800', icon: <FiXCircle className="h-4 w-4" /> }

  return (
    <section
      data-testid="validation-checklist"
      aria-label="Shipment check"
      className="max-h-[48vh] overflow-y-auto rounded-2xl border border-[#e3d9c4] bg-white p-3.5 shadow-[0_18px_50px_rgba(31,21,12,0.14)]"
    >
      <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-[11px] font-bold uppercase tracking-[0.12em] text-[#5a4526]">Shipment check</p>
          <p className={`mt-0.5 flex items-center gap-1.5 text-[13px] font-semibold ${headline.tone}`}>
            {headline.icon}
            {result.message}
          </p>
          <p className="mt-0.5 text-[11px] text-slate-500">
            {checkedAt ? `Checked at ${checkedAt.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}` : null}
            {stale ? (
              <span className="ml-2 rounded-full bg-amber-50 px-1.5 py-0.5 font-semibold text-amber-800 ring-1 ring-amber-200">
                The form changed — check again
              </span>
            ) : null}
          </p>
        </div>
        <div className="flex items-center gap-1.5">
          <button type="button" onClick={onRevalidate} disabled={busy}
            className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] px-2 py-1 text-[11.5px] font-semibold text-[#5a4526] hover:bg-[#faf7f0] disabled:opacity-50">
            <FiRefreshCw className={`h-3 w-3 ${busy ? 'animate-spin' : ''}`} /> Check again
          </button>
          <button type="button" onClick={onClose} aria-label="Close the shipment check"
            className="rounded-lg p-1 text-[#6b5c42] hover:bg-[#faf7f0]">
            <FiX className="h-4 w-4" />
          </button>
        </div>
      </div>

      <ul className="divide-y divide-[#efe7d6] rounded-xl border border-[#efe7d6]">
        {groups.map((g) => {
          const tone = TONE[g.status]
          const isOpen = open.has(g.key)
          const hasDetail = g.errors.length + g.warnings.length + g.skipped.length > 0
            || (g.key === 'service' && !!result.quote)
          return (
            <li key={g.key} data-testid={`check-${g.key}`} data-status={g.status}>
              <button type="button" onClick={() => hasDetail && toggle(g.key)} aria-expanded={hasDetail ? isOpen : undefined}
                className={`flex w-full items-center gap-2 px-3 py-2 text-left ${hasDetail ? 'hover:bg-[#fcfaf5]' : 'cursor-default'}`}>
                {tone.icon}
                <span className="flex-1 text-[12.5px] font-semibold text-[#1f150c]">{g.title}</span>
                <span className={`rounded-full px-1.5 py-0.5 text-[10.5px] font-semibold ring-1 ${tone.chip}`}>{tone.label(g)}</span>
                {hasDetail ? <FiChevronDown className={`h-3.5 w-3.5 text-slate-400 transition ${isOpen ? 'rotate-180' : ''}`} /> : <span className="w-3.5" />}
              </button>
              {isOpen && hasDetail ? (
                <div className="space-y-1.5 px-3 pb-2.5 pl-9 text-[12px]">
                  {g.errors.length ? (
                    <ul className="list-disc space-y-0.5 pl-4 text-rose-800">
                      {g.errors.map((m, i) => <li key={`e${i}`}>{m}</li>)}
                    </ul>
                  ) : null}
                  {g.warnings.length ? (
                    <ul className="list-disc space-y-0.5 pl-4 text-amber-800">
                      {g.warnings.map((m, i) => <li key={`w${i}`}>{m}</li>)}
                    </ul>
                  ) : null}
                  {g.key === 'service' && result.quote ? <ValidationQuote quote={result.quote} /> : null}
                  {g.skipped.length ? (
                    <ul className="space-y-0.5 text-slate-500">
                      {g.skipped.map((m, i) => <li key={`s${i}`}>Not checked — {m}</li>)}
                    </ul>
                  ) : null}
                  {g.errors.length || g.warnings.length ? (
                    <button type="button" onClick={() => goTo(g.sectionId)}
                      className="text-[11.5px] font-semibold text-[#5a4526] underline-offset-2 hover:underline">
                      Go to {g.title.toLowerCase()}
                    </button>
                  ) : null}
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
