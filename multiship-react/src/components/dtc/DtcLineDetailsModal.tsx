import { useEffect, useRef, type ReactNode } from 'react'
import { FiAlertCircle, FiEdit2, FiExternalLink, FiMapPin, FiPackage, FiTag, FiX } from 'react-icons/fi'
import { lineHasError, type DtcOrder } from '../../api/dtcService'
import { useFocusTrap } from '../../hooks/useFocusTrap'

/**
 * One D2C shipment line, read straight off the dtc_orders row (so it works for
 * lines that have no label order yet). Opened from the Order No link on DTC
 * Shipment History. Three cards — Ship to, Shipment, Label — and the actions the
 * line allows in the footer.
 */
export default function DtcLineDetailsModal({ line: o, onClose, onEdit, onOpenOrder }: {
  line: DtcOrder
  onClose: () => void
  /** Fix a line whose label run failed — present only then. */
  onEdit?: () => void
  /** Open the label order's own details — present once there is one. */
  onOpenOrder?: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, dialogRef)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const errored = lineHasError(o)
  const status = statusOf(o)
  const street = [o.shipAddr1, o.shipAddr2, o.shipAddr3].filter(Boolean)
  const locality = [o.shipToCity, [o.shipToState, o.shipToZip].filter(Boolean).join(' ')].filter(Boolean).join(', ')
  const country = o.shipToCountryCode ?? o.countryName

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={`Shipment line, order ${o.orderNo ?? '—'}`}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        onClick={(e) => e.stopPropagation()}
        className="bulk-pop-in flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-[0_30px_80px_rgba(31,21,12,0.30)]"
      >
        {/* Header: which line, whose, and where its label stands */}
        <div className="flex items-start justify-between gap-4 border-b border-[#f2ecdf] bg-[#fcfaf5] px-6 py-4">
          <div className="min-w-0">
            <p className="text-[10.5px] font-semibold uppercase tracking-[0.12em] text-[#a1906d]">Shipment line</p>
            <h3 className="mt-0.5 flex flex-wrap items-center gap-2 text-[17px] font-semibold tracking-tight text-[#1f150c]">
              Order {o.orderNo ?? '—'}
              <span className={`rounded-full border px-2 py-0.5 text-[10.5px] font-semibold ${status.cls}`}>{status.label}</span>
            </h3>
            <p className="mt-1 text-[12px] text-[#6b5c42]">
              Tote <span className="font-mono">{o.toteNumber ?? '—'}</span>
              <span className="mx-1.5 text-[#cdbf9f]">·</span>Client {o.tenantId}
              <span className="mx-1.5 text-[#cdbf9f]">·</span>Batch {o.batchId}
              {o.shipDate ? <><span className="mx-1.5 text-[#cdbf9f]">·</span>Ships {o.shipDate}</> : null}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="shrink-0 rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#5a4526] transition hover:bg-[#faf7f0]">
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-4 overflow-y-auto px-6 py-5">
          {/* What went wrong, first — it's why someone opens a failed line */}
          {o.generatedMessage ? (
            <div className={`flex items-start gap-2.5 rounded-xl border px-3.5 py-2.5 text-[12.5px] ${
              errored ? 'border-red-200 bg-red-50 text-red-800' : 'border-[#e3d9c4] bg-[#fcfaf5] text-[#5a4526]'}`}>
              {errored ? <FiAlertCircle className="mt-0.5 h-4 w-4 shrink-0" /> : null}
              <p className="min-w-0 break-words">{o.generatedMessage}</p>
            </div>
          ) : null}

          <div className="grid gap-4 sm:grid-cols-2">
            <Card icon={<FiMapPin className="h-3.5 w-3.5" />} title="Ship to">
              <p className="text-[13.5px] font-semibold text-[#1f150c]">{o.shipName ?? '—'}</p>
              {o.shipAttn ? <p className="text-[12.5px] text-[#5a4526]">{o.shipAttn}</p> : null}
              <div className="mt-1.5 space-y-0.5 text-[12.5px] leading-relaxed text-[#3d2f1c]">
                {street.length ? street.map((l) => <p key={l}>{l}</p>) : <p className="text-[#a1906d]">No street address</p>}
                {locality ? <p>{locality}</p> : null}
                {country ? <p className="font-semibold">{country}</p> : null}
              </div>
              {o.phone || o.email ? (
                <div className="mt-2.5 space-y-0.5 border-t border-[#f2ecdf] pt-2 text-[12px] text-[#5a4526]">
                  {o.phone ? <p>{o.phone}</p> : null}
                  {o.email ? <p className="break-all">{o.email}</p> : null}
                </div>
              ) : null}
            </Card>

            <Card icon={<FiPackage className="h-3.5 w-3.5" />} title="Shipment">
              <Rows rows={[
                ['Goods', o.goodsDesc],
                ['Weight', o.weight != null ? `${o.weight} lb` : null],
                ['Value', o.unitValue != null ? `${Number(o.unitValue).toFixed(2)} USD` : null],
                ['Ship via', shipViaLabel(o)],
                ['Customer', o.custNo],
                ['PO', o.custPo],
                ['Terms', o.termsCode],
                ['Location', o.location],
                ['International', o.intlYn === 'Y' ? 'Yes' : o.intlYn === 'N' ? 'No' : o.intlYn],
              ]} />
            </Card>
          </div>

          <Card icon={<FiTag className="h-3.5 w-3.5" />} title="Label">
            <div className="grid gap-x-6 sm:grid-cols-2">
              <Rows rows={[
                ['Status', status.label],
                ['Carrier', o.generatedCarrierCode],
                ['Label order', o.generatedOrderNo != null ? String(o.generatedOrderNo) : null],
              ]} />
              <Rows rows={[
                ['Tracking', o.generatedTrackingNumber, true],
                ['Generated', formatWhen(o.generatedAt)],
              ]} />
            </div>
          </Card>
        </div>

        <div className="flex flex-wrap items-center justify-end gap-2 border-t border-[#f2ecdf] bg-[#fcfaf5] px-6 py-3">
          {onOpenOrder ? (
            <button type="button" onClick={onOpenOrder} className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]">
              <FiExternalLink className="h-3.5 w-3.5" /> Label order details
            </button>
          ) : null}
          {onEdit ? (
            <button type="button" onClick={onEdit} className="inline-flex items-center gap-1.5 rounded-lg border border-amber-300 bg-amber-50 px-3 py-1.5 text-[12px] font-semibold text-amber-800 transition hover:bg-amber-100">
              <FiEdit2 className="h-3.5 w-3.5" /> Edit line
            </button>
          ) : null}
          <button type="button" onClick={onClose} className="rounded-lg bg-[#1f150c] px-3.5 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15]">
            Close
          </button>
        </div>
      </div>
    </div>
  )
}

function Card({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-[#efe7d6] bg-white p-4">
      <h4 className="mb-2.5 flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.1em] text-[#8a7a5a]">
        <span className="inline-flex h-6 w-6 items-center justify-center rounded-md bg-[#f4eede] text-[#412d15]" aria-hidden="true">{icon}</span>
        {title}
      </h4>
      {children}
    </section>
  )
}

/** Label / value rows; empty values show a quiet dash so the grid stays aligned. */
function Rows({ rows }: { rows: [string, string | null | undefined, boolean?][] }) {
  return (
    <dl className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[12.5px]">
      {rows.map(([label, value, mono]) => (
        <div key={label} className="contents">
          <dt className="text-[#a1906d]">{label}</dt>
          <dd className={`min-w-0 break-words ${value ? `font-medium text-[#1f150c] ${mono ? 'font-mono text-[12px]' : ''}` : 'text-[#cdbf9f]'}`}>{value || '—'}</dd>
        </div>
      ))}
    </dl>
  )
}

function statusOf(o: DtcOrder): { label: string; cls: string } {
  switch (o.generatedStatus) {
    case 'GENERATED': return { label: 'Generated', cls: 'border-emerald-200 bg-emerald-50 text-emerald-700' }
    case 'QUEUED_USPS': return { label: 'Queued at USPS', cls: 'border-emerald-200 bg-emerald-50 text-emerald-700' }
    case 'FAILED': return { label: 'Failed', cls: 'border-red-200 bg-red-50 text-red-700' }
    case 'IN_FLIGHT': return { label: 'Buying…', cls: 'border-amber-200 bg-amber-50 text-amber-700' }
    default: return { label: 'Not generated', cls: 'border-slate-200 bg-slate-50 text-slate-600' }
  }
}

function shipViaLabel(o: DtcOrder) {
  const code = o.shipViaCode?.trim()
  const text = o.shipVia?.trim()
  if (code && text && code.toLowerCase() !== text.toLowerCase()) return `${code} · ${text}`
  return code || text || null
}

function formatWhen(iso: string | null) {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}
