import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import { FiExternalLink, FiGlobe, FiMapPin, FiPackage, FiPhone, FiTag, FiTruck, FiX } from 'react-icons/fi'
import {
  orderService,
  type LabelDetails,
  type OrderWithLinesPayload,
} from '../../api/orderService'
import { formatCarrierName } from '../../utils/carrierUtils'
import CarrierLogo from '../workspace/CarrierLogo'
import OrderStatusBadge from '../workspace/OrderStatusBadge'
import CustomsEditorModal from './CustomsEditorModal'

interface OrderDetailsModalProps {
  orderNo: number
  onClose: () => void
}

const formatMoney = (value: number | null | undefined) =>
  typeof value === 'number' ? `$${value.toFixed(2)}` : '—'

const formatDate = (value?: string | null) => {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' })
}

const COUNTRY_NAMES: Record<string, string> = {
  US: 'United States',
  IN: 'India',
  GB: 'United Kingdom',
  CA: 'Canada',
  FR: 'France',
  DE: 'Germany',
  AU: 'Australia',
}

const SCENARIO_LABEL: Record<string, string> = {
  ORDER: 'From order details',
  REFERENCE: 'Saved account',
  DEFAULT: 'Company default',
}

/**
 * Full order drill-down: recipient, shipping account (three-scenario cascade),
 * label/tracking status, and customs line items with totals. Backed by
 * GET /orders/{orderNo}/with-lines plus /orders/{orderNo} for label status.
 */
export default function OrderDetailsModal({ orderNo, onClose }: OrderDetailsModalProps) {
  // Sprint 51 T6b — focus trap.
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, dialogRef)
  const navigate = useNavigate()
  const [payload, setPayload] = useState<OrderWithLinesPayload | null>(null)
  const [label, setLabel] = useState<LabelDetails | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [customsOpen, setCustomsOpen] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    Promise.all([
      orderService.getOrderWithLines(orderNo),
      orderService.getOrderById(orderNo).catch(() => null),
    ])
      .then(([withLines, byId]) => {
        if (cancelled) return
        setPayload(withLines.data)
        setLabel(byId?.data?.labelDetails ?? null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load order details.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [orderNo])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const order = payload?.order
  const account = payload?.carrierAccount
  const lines = order?.orderLines ?? []

  const totals = useMemo(
    () => ({
      qty: lines.reduce((s, l) => s + (l.qtyShipped ?? 0), 0),
      value: lines.reduce((s, l) => s + (l.totalPrice ?? 0), 0),
      customs: lines.reduce((s, l) => s + (l.customsDeclValue ?? 0), 0),
    }),
    [lines]
  )

  // Recipient name: some feeds put a bare sequence digit in ship_name.
  const shipName = order?.shipName?.trim()
  const recipient =
    (shipName && shipName.length > 2 ? shipName : order?.shipAttn) || order?.custNo || 'Consignee'
  const attnDiffers = order?.shipAttn && order.shipAttn !== recipient
  const country = order?.shiptoCountryCd
    ? COUNTRY_NAMES[order.shiptoCountryCd.toUpperCase()] || order.shiptoCountryCd.toUpperCase()
    : null
  // International = destination differs from our home country. Customs only
  // applies to cross-border shipments, so the button appears only then.
  const isInternational = Boolean(
    order?.shiptoCountryCd && order.shiptoCountryCd.trim().toUpperCase() !== 'US'
  )

  const openLabel = () => {
    onClose()
    navigate(`/label/${orderNo}`)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={`Order ${orderNo} details`}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[88vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(event) => event.stopPropagation()}
      >
        {/* header */}
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400">Order details</p>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <h3 className="text-base font-semibold text-slate-950">Order #{orderNo}</h3>
              {label?.status ? <OrderStatusBadge status={label.status} /> : null}
              {order?.tenantId ? (
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-500">
                  Tenant {order.tenantId}
                </span>
              ) : null}
            </div>
          </div>
          <div className="flex items-center gap-2">
            {isInternational ? (
              <button
                type="button"
                onClick={() => setCustomsOpen(true)}
                className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50"
              >
                <FiGlobe className="h-3.5 w-3.5" />
                Customs
              </button>
            ) : null}
            <button
              type="button"
              onClick={openLabel}
              className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
            >
              <FiTag className="h-3.5 w-3.5" />
              View label
            </button>
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
              aria-label="Close"
            >
              <FiX className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* body */}
        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <div className="px-2 py-12 text-center text-sm text-slate-500">Loading order details…</div>
          ) : error ? (
            <div className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-6 text-center text-xs font-semibold text-rose-700">
              {error}
            </div>
          ) : order ? (
            <div className="space-y-4">
              {/* tracking strip (only when generated) */}
              {label?.trackingNumber ? (
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-emerald-200 bg-emerald-50/70 px-4 py-2.5">
                  <div className="flex items-center gap-2 text-[12.5px]">
                    <FiTruck className="h-4 w-4 text-emerald-700" />
                    <span className="font-semibold text-slate-700">Tracking</span>
                    <span className="font-mono font-semibold text-slate-950">{label.trackingNumber}</span>
                    {label.generatedAt ? (
                      <span className="text-slate-500">· generated {formatDate(label.generatedAt)}</span>
                    ) : null}
                  </div>
                  {label.trackingUrl ? (
                    <a
                      href={label.trackingUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1.5 rounded-xl border border-emerald-300 bg-white px-2.5 py-1 text-[11px] font-semibold text-emerald-700 transition hover:bg-emerald-50"
                    >
                      <FiExternalLink className="h-3 w-3" />
                      Track
                    </a>
                  ) : null}
                </div>
              ) : null}

              {/* ship-to + account + order-meta */}
              <div className="grid gap-2.5 md:grid-cols-2">
                <Card label="Ship to" icon={<FiMapPin className="h-3.5 w-3.5" />}>
                  <p className="text-sm font-semibold text-slate-950">{recipient}</p>
                  {attnDiffers ? <p className="text-xs text-slate-500">ATTN: {order.shipAttn}</p> : null}
                  {order.shipAddr1?.trim() ? (
                    <p className="mt-1 text-[13px] text-slate-700">{order.shipAddr1.trim()}</p>
                  ) : null}
                  <p className="text-[13px] font-medium text-slate-800">
                    {order.shiptoCity}, {order.shiptoState} {order.shiptoZip}
                  </p>
                  {country ? <p className="text-[13px] text-slate-700">{country}</p> : null}
                  {order.phone?.trim() ? (
                    <p className="mt-1.5 inline-flex items-center gap-1.5 text-xs text-slate-500">
                      <FiPhone className="h-3 w-3" />
                      {order.phone.trim()}
                    </p>
                  ) : null}
                </Card>

                <div className="space-y-2.5">
                  <Card label="Ships with" icon={<FiTruck className="h-3.5 w-3.5" />}>
                    {account ? (
                      <div className="flex items-start gap-2.5">
                        <span className="shrink-0 rounded-xl border border-slate-200 bg-white p-1.5 shadow-sm">
                          <CarrierLogo carrierId={account.carrierCode} size={16} className="rounded-sm" />
                        </span>
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5">
                            <p className="truncate text-sm font-semibold text-slate-950">
                              {account.carrierName || formatCarrierName(account.carrierCode)}
                            </p>
                            {account.accountCode && SCENARIO_LABEL[account.accountCode] ? (
                              <span className="shrink-0 rounded-full bg-sky-100 px-2 py-0.5 text-[10px] font-semibold text-sky-700">
                                {SCENARIO_LABEL[account.accountCode]}
                              </span>
                            ) : null}
                          </div>
                          <p className="mt-0.5 truncate text-xs text-slate-500" title={account.accountNumber || undefined}>
                            {account.accountNumber || '—'} · {account.environment || 'SANDBOX'}
                          </p>
                        </div>
                      </div>
                    ) : (
                      <p className="text-xs font-semibold text-amber-700">
                        No account resolved yet — set a company default or add carrier details.
                      </p>
                    )}
                  </Card>

                  <Card label="Order">
                    <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-[12.5px]">
                      <Meta k="Customer" v={order.custNo} />
                      <Meta k="Ship via" v={order.shipviaCd} />
                      <Meta k="Weight" v={order.weight != null ? `${order.weight} kg` : '—'} />
                      <Meta k="Created" v={formatDate(order.createdDate)} />
                    </dl>
                    {order.goodsDesc ? (
                      <p className="mt-1.5 border-t border-slate-200 pt-1.5 text-xs text-slate-600">
                        {order.goodsDesc}
                      </p>
                    ) : null}
                  </Card>
                </div>
              </div>

              {/* line items */}
              <div>
                <div className="flex items-center gap-2">
                  <FiPackage className="h-3.5 w-3.5 text-slate-400" />
                  <h4 className="text-sm font-semibold text-slate-950">Line items ({lines.length})</h4>
                </div>

                <div className="mt-2 overflow-x-auto rounded-2xl border border-slate-200">
                  <table className="w-full min-w-[620px] text-[12px] text-slate-700">
                    <thead className="border-b border-slate-200 bg-slate-50 text-left text-[10px] uppercase tracking-[0.12em] text-slate-500">
                      <tr>
                        <th className="px-3 py-2.5">#</th>
                        <th className="px-3 py-2.5">Item</th>
                        <th className="px-3 py-2.5">Description</th>
                        <th className="px-3 py-2.5 text-right">Qty</th>
                        <th className="px-3 py-2.5 text-right">Unit</th>
                        <th className="px-3 py-2.5 text-right">Total</th>
                        <th className="px-3 py-2.5 text-right">Customs</th>
                        <th className="px-3 py-2.5">HS Code</th>
                        <th className="px-3 py-2.5">Origin</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {lines.map((line) => (
                        <tr key={line.id} className="align-top">
                          <td className="px-3 py-2.5 font-semibold text-slate-950">{line.lineNo}</td>
                          <td className="px-3 py-2.5 font-medium">{line.itemNo || '—'}</td>
                          <td className="px-3 py-2.5">
                            <span className="font-medium text-slate-800">
                              {line.itemDescription || line.description || '—'}
                            </span>
                            {line.hsDesc ? (
                              <span className="block text-[10.5px] text-slate-400">{line.hsDesc}</span>
                            ) : null}
                          </td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{line.qtyShipped ?? '—'}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{formatMoney(line.unitPrice)}</td>
                          <td className="px-3 py-2.5 text-right font-semibold tabular-nums">{formatMoney(line.totalPrice)}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums text-slate-500">{formatMoney(line.customsDeclValue)}</td>
                          <td className="px-3 py-2.5 font-mono text-[11px]">{line.hsCode || '—'}</td>
                          <td className="px-3 py-2.5">{line.countryOfOrigin || '—'}</td>
                        </tr>
                      ))}

                      {!lines.length ? (
                        <tr>
                          <td colSpan={9} className="px-3 py-8 text-center text-xs text-slate-500">
                            No line items are recorded for this order.
                          </td>
                        </tr>
                      ) : null}
                    </tbody>
                    {lines.length ? (
                      <tfoot className="border-t-2 border-slate-200 bg-slate-50 text-[12px] font-semibold text-slate-900">
                        <tr>
                          <td className="px-3 py-2.5" colSpan={3}>
                            Totals · {lines.length} line{lines.length === 1 ? '' : 's'}
                          </td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{totals.qty}</td>
                          <td className="px-3 py-2.5" />
                          <td className="px-3 py-2.5 text-right tabular-nums">{formatMoney(totals.value)}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums text-slate-500">{formatMoney(totals.customs)}</td>
                          <td className="px-3 py-2.5" colSpan={2} />
                        </tr>
                      </tfoot>
                    ) : null}
                  </table>
                </div>
              </div>
            </div>
          ) : null}
        </div>
      </div>

      {customsOpen ? (
        <CustomsEditorModal orderNo={orderNo} onClose={() => setCustomsOpen(false)} />
      ) : null}
    </div>
  )
}

function Card({ label, icon, children }: { label: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-slate-50/70 p-3.5">
      <p className="mb-1.5 inline-flex items-center gap-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
        {icon}
        {label}
      </p>
      {children}
    </div>
  )
}

function Meta({ k, v }: { k: string; v: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-[10px] font-semibold uppercase tracking-[0.1em] text-slate-400">{k}</dt>
      <dd className="font-medium text-slate-800">{v || '—'}</dd>
    </div>
  )
}
