import { useEffect, useState } from 'react'
import { FiAlertCircle, FiCheckCircle, FiPackage, FiX } from 'react-icons/fi'
import {
  landedCostService,
  type LandedCostRequest,
  type LandedCostResponse,
} from '../../api/landedCostService'
import { notify } from '../../utils/notify'

/**
 * Sprint 32 — landed cost breakdown modal. Calls
 * {@code POST /api/v1/landed-cost} on mount with the parent's request
 * and renders the total breakdown (freight + duty + tax + other).
 *
 * source values drive the display:
 *   LIVE          — full breakdown card + optional line items.
 *   NOT_SUPPORTED — grey banner explaining why (domestic lane, USPS,
 *                   no credentials).
 *   ERROR         — red banner with the carrier's failure reason.
 */
export interface LandedCostModalProps {
  request: LandedCostRequest
  onClose: () => void
}

export default function LandedCostModal({ request, onClose }: LandedCostModalProps) {
  const [data, setData] = useState<LandedCostResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const response = await landedCostService.estimate(request)
        if (!cancelled) setData(response.data ?? null)
      } catch (e) {
        const msg = e instanceof Error ? e.message : 'Landed cost call failed.'
        if (!cancelled) setError(msg)
        notify.error(msg)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [request])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Landed cost estimate"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(620px,90vh)] w-full max-w-[560px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Landed cost
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Freight + duties + taxes
              {data?.carrierCode ? (
                <span className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-[0.1em] text-slate-500">
                  {data.carrierCode}
                </span>
              ) : null}
            </h3>
            <p className="mt-1 text-[11.5px] text-slate-500">
              Estimated by the carrier's duty engine. Actual duties collected at import may differ.
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

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <p className="py-10 text-center text-[12.5px] text-slate-500">
              Asking the carrier for a landed cost estimate…
            </p>
          ) : error ? (
            <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-3 text-[12px] text-rose-700">
              <FiAlertCircle className="mr-1.5 inline h-3.5 w-3.5" />
              {error}
            </div>
          ) : data ? (
            <ResultBody data={data} />
          ) : null}
        </div>
      </div>
    </div>
  )
}

function ResultBody({ data }: { data: LandedCostResponse }) {
  if (data.source === 'NOT_SUPPORTED') {
    return (
      <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-3 text-[12px] text-slate-600">
        <p className="font-semibold text-slate-700">Landed cost not available</p>
        <p className="mt-1">{data.message}</p>
        {data.warnings.length > 0 ? (
          <ul className="mt-2 list-disc space-y-0.5 pl-5 text-[11px]">
            {data.warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        ) : null}
      </div>
    )
  }
  if (data.source === 'ERROR') {
    return (
      <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-3 text-[12px] text-rose-800">
        <p className="flex items-center gap-1.5 font-semibold">
          <FiAlertCircle className="h-3.5 w-3.5" /> Carrier failed to return an estimate
        </p>
        <p className="mt-1">{data.message}</p>
      </div>
    )
  }
  return <LiveBreakdown data={data} />
}

function LiveBreakdown({ data }: { data: LandedCostResponse }) {
  const currency = data.currency ?? 'USD'
  const fmt = (v: number | null | undefined) =>
    v == null
      ? '—'
      : (() => {
          try {
            return new Intl.NumberFormat(undefined, { style: 'currency', currency, minimumFractionDigits: 2 }).format(v)
          } catch {
            return `${currency} ${v.toFixed(2)}`
          }
        })()

  return (
    <>
      <div className="mb-3 rounded-2xl border border-emerald-200 bg-emerald-50/60 p-4">
        <p className="flex items-center gap-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-emerald-700">
          <FiCheckCircle className="h-3 w-3" /> Estimate
        </p>
        <p className="mt-1 text-[26px] font-semibold text-slate-950">{fmt(data.grandTotal)}</p>
        <div className="mt-3 grid grid-cols-2 gap-2 text-[11.5px] text-slate-600">
          <LineRow label="Freight" value={fmt(data.freightAmount)} />
          <LineRow label="Duty" value={fmt(data.dutyTotal)} />
          <LineRow label="Tax" value={fmt(data.taxTotal)} />
          {data.otherTotal ? <LineRow label="Other fees" value={fmt(data.otherTotal)} /> : null}
        </div>
      </div>

      {data.lineItems.length > 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-3">
          <p className="mb-2 flex items-center gap-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
            <FiPackage className="h-3 w-3" /> Per commodity
          </p>
          <table className="w-full text-left text-[11.5px] text-slate-700">
            <thead className="text-[9.5px] uppercase tracking-[0.14em] text-slate-400">
              <tr>
                <th className="pb-1">Item</th>
                <th className="pb-1 text-right">Value</th>
                <th className="pb-1 text-right">Duty</th>
                <th className="pb-1 text-right">Tax</th>
              </tr>
            </thead>
            <tbody>
              {data.lineItems.map((l, i) => (
                <tr key={i} className="border-t border-slate-100">
                  <td className="py-1.5">
                    <p className="font-medium text-slate-950">{l.description ?? 'Unknown item'}</p>
                    {l.hsCode ? <p className="text-[10px] font-mono text-slate-500">HS {l.hsCode}</p> : null}
                  </td>
                  <td className="py-1.5 text-right">{fmt(l.declaredValue)}</td>
                  <td className="py-1.5 text-right">{fmt(l.dutyAmount)}</td>
                  <td className="py-1.5 text-right">{fmt(l.taxAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {data.warnings.length > 0 ? (
        <div className="mt-3 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[11.5px] text-amber-800">
          <ul className="list-disc space-y-0.5 pl-5">
            {data.warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </>
  )
}

function LineRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between rounded-lg bg-white/60 px-2 py-1">
      <span className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">{label}</span>
      <span className="font-mono font-semibold text-slate-950">{value}</span>
    </div>
  )
}
