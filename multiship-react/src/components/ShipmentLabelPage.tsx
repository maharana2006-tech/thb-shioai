import { useNavigate, useParams } from 'react-router-dom'
import { FiAlertTriangle, FiRefreshCw, FiTag } from 'react-icons/fi'
import { useLabelArtifact } from '../hooks/useLabelArtifact'
import { LabelActions } from './label/LabelActions'
import { LabelHeader } from './label/LabelHeader'
import { LabelMeta } from './label/LabelMeta'
import { LabelPreview } from './label/LabelPreview'

/**
 * Phase 2 (Shipment & Label re-architecture) — the route shell. One hook,
 * one state, four components. The label is the hero: it is what the
 * operator is here to check. Everything else is subordinate to it.
 */
export default function ShipmentLabelPage() {
  const { orderNo: orderNoParam } = useParams<{ orderNo: string }>()
  const navigate = useNavigate()
  const orderNo = orderNoParam && /^\d+$/.test(orderNoParam) ? Number(orderNoParam) : null
  const { view, setActiveSeq, reload } = useLabelArtifact(orderNo)

  const shell = 'mx-auto max-w-6xl px-6 py-4'
  const primary =
    'inline-flex items-center gap-2 rounded-xl bg-[#1f150c] px-4 py-2 text-[13px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#1f150c]'
  const secondary =
    'inline-flex items-center gap-2 rounded-xl border border-[#e3d9c4] bg-white px-4 py-2 text-[13px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]'

  if (view.status === 'loading') {
    return (
      <div className={shell} role="status" aria-live="polite">
        <div className="h-5 w-40 animate-pulse rounded bg-[#f0e9d8]" />
        <div className="mt-6 grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="aspect-[4/5] animate-pulse rounded-2xl bg-[#f0e9d8]" />
          <div className="grid content-start gap-4">
            <div className="h-40 animate-pulse rounded-2xl bg-[#f0e9d8]" />
            <div className="h-56 animate-pulse rounded-2xl bg-[#f0e9d8]" />
          </div>
        </div>
        <span className="sr-only">Loading label…</span>
      </div>
    )
  }

  if (view.status === 'error') {
    return (
      <div className="mx-auto max-w-xl px-6 py-10">
        <div role="alert" className="rounded-2xl border border-rose-200 bg-rose-50 p-5">
          <div className="flex items-start gap-3">
            <FiAlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-rose-600" />
            <div className="min-w-0">
              <p className="text-[15px] font-semibold text-rose-900">Couldn't load this label.</p>
              <p className="mt-1 text-[13px] text-rose-800">{view.message}</p>
            </div>
          </div>
          <div className="mt-4 flex gap-2">
            <button type="button" onClick={reload} className={primary}>
              <FiRefreshCw className="h-3.5 w-3.5" /> Retry
            </button>
            <button type="button" onClick={() => navigate(-1)} className={secondary}>
              Back
            </button>
          </div>
        </div>
      </div>
    )
  }

  if (view.status === 'empty') {
    return (
      <div className={shell}>
        <LabelHeader artifact={view.artifact} />
        <div className="mt-6 rounded-2xl border border-dashed border-[#cdbf9f] bg-[#faf7f0] px-6 py-14 text-center">
          <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-[#f0e9d8] text-[#a1906d]">
            <FiTag className="h-5 w-5" />
          </span>
          <p className="mt-4 text-[18px] font-semibold text-[#1f150c]">No label yet</p>
          <p className="mx-auto mt-1 max-w-md text-[13px] leading-relaxed text-[#6b5c42]">
            This order has no generated label. Generate one from the order, and it will appear here with its ZPL,
            preview and PDF all from the same source.
          </p>
          <button
            type="button"
            onClick={() => navigate(`/orders/new?fixOrder=${view.artifact.orderNo}`)}
            className={`${primary} mt-5`}
          >
            Generate label
          </button>
        </div>
      </div>
    )
  }

  const { artifact, activeSeq } = view
  const pkg = artifact.packages.find((p) => p.seq === activeSeq) ?? artifact.packages[0]

  return (
    <div className={shell}>
      <LabelHeader artifact={artifact} />
      <div className="mt-4 grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
        <LabelPreview artifact={artifact} activeSeq={activeSeq} onSelectSeq={setActiveSeq} />
        <div className="grid content-start gap-3 lg:sticky lg:top-4">
          <LabelMeta artifact={artifact} pkg={pkg} />
          <LabelActions artifact={artifact} activeSeq={activeSeq} />
        </div>
      </div>
    </div>
  )
}
