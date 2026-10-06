import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import { FiAlertTriangle, FiExternalLink, FiFileText, FiGlobe, FiList, FiMapPin, FiPackage, FiTag, FiTruck, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import {
  orderService,
  type LabelDetails,
  type OrderWithLines,
  type OrderWithLinesPayload,
} from '../../api/orderService'
import { customsService, type OrderCustoms } from '../../api/customsService'
import { formatCarrierName } from '../../utils/carrierUtils'
import { countryName } from '../../utils/countries'
import { ftrExemptionLabel, SHIPPING_PURPOSES } from '../../utils/customsOptions'
import CarrierLogo from '../workspace/CarrierLogo'
import OrderStatusBadge from '../workspace/OrderStatusBadge'
import { Card, Rows } from './DetailCards'

interface OrderDetailsModalProps {
  orderNo: number
  onClose: () => void
}

const SCENARIO_LABEL: Record<string, string> = {
  ORDER: 'From order details',
  REFERENCE: 'Saved account',
  DEFAULT: 'Company default',
}

const DUTIES_LABEL: Record<string, string> = {
  SENDER: 'Sender',
  RECIPIENT: 'Recipient',
  RECEIVER: 'Recipient',
  THIRD_PARTY: 'Third party',
}

const money = (value: number | null | undefined, currency = 'USD') => {
  if (value == null || Number.isNaN(Number(value))) return null
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(Number(value))
  } catch {
    return `${Number(value).toFixed(2)} ${currency}`
  }
}

const formatDate = (value?: string | null) => {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' })
}

const clean = (v?: string | null) => v?.trim() || null

/**
 * Full label-order drill-down: where it ships from and to, how (carrier
 * account), the label/tracking state, the customs declaration inline
 * (international) and the goods. Backed by GET /orders/{n}/with-lines,
 * /orders/{n} (label status) and /orders/{n}/customs.
 */
export default function OrderDetailsModal({ orderNo, onClose }: OrderDetailsModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, dialogRef)
  const navigate = useNavigate()
  const [payload, setPayload] = useState<OrderWithLinesPayload | null>(null)
  const [label, setLabel] = useState<LabelDetails | null>(null)
  const [customs, setCustoms] = useState<OrderCustoms | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading + clear stale error before async order-detail fetch
    setLoading(true)
    setError(null)

    Promise.all([
      orderService.getOrderWithLines(orderNo),
      orderService.getOrderById(orderNo).catch((e: unknown) => {
        notify.apiError(e, 'Order loaded, but the label details fetch failed. Label / tracking info may be missing below.')
        return null
      }),
      // Best-effort: a 422 (no customs recorded) just leaves it null.
      customsService.getCustoms(orderNo).catch(() => null),
    ])
      .then(([withLines, byId, customsData]) => {
        if (cancelled) return
        setPayload(withLines.data)
        setLabel(byId?.data?.labelDetails ?? null)
        setCustoms(customsData)
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
  const voided = (label?.status || '').toUpperCase() === 'VOIDED'
  const destination = clean(order?.shiptoCountryCd)?.toUpperCase()
  const origin = clean(order?.shipFromCountryCd)?.toUpperCase() || 'US'
  const isInternational = order?.intlYn === 'Y' || Boolean(destination && destination !== origin)

  const openLabel = () => {
    onClose()
    navigate(`/label/${orderNo}`)
  }

  // Open the platform's own commercial-invoice PDF in a new tab. Fetched as a
  // blob (same-origin, auth cookie) so a 422 surfaces as a toast, not a broken tab.
  const [invoiceBusy, setInvoiceBusy] = useState(false)
  const openCommercialInvoice = async () => {
    if (invoiceBusy) return
    setInvoiceBusy(true)
    try {
      const blob = await orderService.getCommercialInvoicePdf(orderNo)
      const url = URL.createObjectURL(blob)
      window.open(url, '_blank', 'noopener')
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (err) {
      notify.error(err instanceof Error ? err.message : 'Could not open the commercial invoice.')
    } finally {
      setInvoiceBusy(false)
    }
  }

  const btn = 'inline-flex items-center gap-1.5 rounded-lg border border-[var(--e-e3d9c4)] bg-white px-3 py-1.5 text-[12px] font-semibold text-[var(--e-5a4526)] transition hover:bg-[var(--e-faf7f0)] disabled:opacity-50'

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
        className="bulk-pop-in flex max-h-[90vh] w-full max-w-4xl flex-col overflow-hidden rounded-2xl border border-[var(--e-e3d9c4)] bg-white shadow-[0_30px_80px_rgba(31,21,12,0.30)]"
        onClick={(event) => event.stopPropagation()}
      >
        {/* Header: which order, whose, and its label state */}
        <div className="flex items-start justify-between gap-4 border-b border-[var(--e-f2ecdf)] bg-[var(--e-fcfaf5)] px-6 py-4">
          <div className="min-w-0">
            <p className="text-[10.5px] font-semibold uppercase tracking-[0.12em] text-[var(--e-a1906d)]">Label order</p>
            <h3 className="mt-0.5 flex flex-wrap items-center gap-2 text-[17px] font-semibold tracking-tight text-[var(--e-1f150c)]">
              Order {order?.displayOrderNo || orderNo}
              {label?.status ? <OrderStatusBadge status={label.status} /> : null}
              {order?.orderChannel ? <Chip>{order.orderChannel}</Chip> : null}
              {isInternational ? <Chip><FiGlobe className="h-3 w-3" /> International</Chip> : null}
              {order?.isReturn === 'Y' ? <Chip>Return</Chip> : null}
            </h3>
            {order ? (
              <p className="mt-1 text-[12px] text-[var(--e-6b5c42)]">
                {[
                  order.tenantId ? `Client ${order.tenantId}` : null,
                  formatDate(order.createdDate) ? `Created ${formatDate(order.createdDate)}` : null,
                  clean(order.customerRef),
                ].filter(Boolean).map((part, i) => (
                  <span key={i}>{i ? <span className="mx-1.5 text-[var(--e-cdbf9f)]">·</span> : null}{part}</span>
                ))}
              </p>
            ) : null}
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {isInternational ? (
              <button type="button" onClick={openCommercialInvoice} disabled={invoiceBusy} className={btn}>
                <FiFileText className="h-3.5 w-3.5" />
                {invoiceBusy ? 'Opening…' : 'Commercial invoice'}
              </button>
            ) : null}
            <button
              type="button"
              onClick={openLabel}
              title={voided ? 'This label was voided — the document opens for record-keeping only and must not be used on a parcel.' : undefined}
              className={voided ? btn : 'inline-flex items-center gap-1.5 rounded-lg bg-[var(--e-1f150c)] px-3 py-1.5 text-[12px] font-semibold text-[var(--e-f4eede)] shadow-sm transition hover:bg-[var(--e-412d15)]'}
            >
              <FiTag className="h-3.5 w-3.5" />
              {voided ? 'Voided label (record)' : 'View label'}
            </button>
            <button type="button" onClick={onClose} aria-label="Close" className="rounded-lg border border-[var(--e-e3d9c4)] bg-white p-1.5 text-[var(--e-5a4526)] transition hover:bg-[var(--e-faf7f0)]">
              <FiX className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-5">
          {loading ? (
            <div className="py-12 text-center text-[13px] text-[var(--e-8a7a5a)]">Loading order details…</div>
          ) : error ? (
            <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-6 text-center text-[12.5px] font-semibold text-red-700">{error}</div>
          ) : order ? (
            <div className="space-y-4">
              {label?.trackingNumber ? <TrackingStrip label={label} voided={voided} /> : null}

              <div className={`grid gap-4 sm:grid-cols-2 ${clean(order.shipFromCountryCd) ? 'lg:grid-cols-3' : ''}`}>
                {clean(order.shipFromCountryCd) ? <ShipFrom order={order} /> : null}
                <ShipTo order={order} />
                <Shipment order={order} account={payload?.carrierAccount} />
              </div>

              {customs || isInternational ? (
                <CustomsSection customs={customs} order={order} />
              ) : null}

              {order.orderLines?.length ? <OrderLinesSection order={order} /> : null}

              {!order.orderLines?.length && !customs?.items?.length ? (
                <p className="rounded-xl border border-dashed border-[var(--e-e3d9c4)] px-4 py-5 text-center text-[12px] text-[var(--e-8a7a5a)]">
                  No line items are recorded for this order.
                </p>
              ) : null}

              {(order.packages?.length ?? 0) > 1 ? <PackagesSection order={order} /> : null}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

function Chip({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full border border-[var(--e-e3d9c4)] bg-white px-2 py-0.5 text-[10.5px] font-semibold text-[var(--e-5a4526)]">
      {children}
    </span>
  )
}

function TrackingStrip({ label, voided }: { label: LabelDetails; voided: boolean }) {
  return (
    <div className={`flex flex-wrap items-center justify-between gap-2 rounded-xl border px-4 py-2.5 ${
      voided ? 'border-slate-300 bg-slate-100' : 'border-emerald-200 bg-emerald-50/70'}`}>
      <div className="flex flex-wrap items-center gap-2 text-[12.5px]">
        <FiTruck className={`h-4 w-4 ${voided ? 'text-slate-500' : 'text-emerald-700'}`} />
        <span className="font-semibold text-[var(--e-3d2f1c)]">Tracking</span>
        <span className={`font-mono font-semibold ${voided ? 'text-slate-500 line-through' : 'text-[var(--e-1f150c)]'}`}>{label.trackingNumber}</span>
        {voided ? (
          <span className="rounded-full bg-slate-300 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-slate-700">Voided — cancelled at the carrier</span>
        ) : label.generatedAt ? (
          <span className="text-[var(--e-6b5c42)]">· generated {formatDate(label.generatedAt)}</span>
        ) : null}
      </div>
      {label.trackingUrl ? (
        <a
          href={label.trackingUrl}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-white px-2.5 py-1 text-[11px] font-semibold text-emerald-700 transition hover:bg-emerald-50"
        >
          <FiExternalLink className="h-3 w-3" /> Track
        </a>
      ) : null}
    </div>
  )
}

/** Name, street lines, locality and country — blanks and repeats dropped. */
function Address({ name, sub, lines, city, state, zip, country, phone }: {
  name: string | null
  sub?: string | null
  lines: (string | null | undefined)[]
  city?: string | null
  state?: string | null
  zip?: string | null
  country?: string | null
  phone?: string | null
}) {
  const street = [...new Set(lines.map(clean).filter((l): l is string => !!l))]
  const locality = [clean(city), [clean(state), clean(zip)].filter(Boolean).join(' ')].filter(Boolean).join(', ')
  return (
    <>
      <p className="text-[13.5px] font-semibold text-[var(--e-1f150c)]">{name || '—'}</p>
      {sub ? <p className="text-[12.5px] text-[var(--e-5a4526)]">{sub}</p> : null}
      <div className="mt-1.5 space-y-0.5 text-[12.5px] leading-relaxed text-[var(--e-3d2f1c)]">
        {street.length ? street.map((l) => <p key={l}>{l}</p>) : <p className="text-[var(--e-a1906d)]">No street address</p>}
        {locality ? <p>{locality}</p> : null}
        {clean(country) ? <p className="font-semibold">{countryName(country)}</p> : null}
      </div>
      {clean(phone) ? (
        <p className="mt-2.5 border-t border-[var(--e-f2ecdf)] pt-2 text-[12px] text-[var(--e-5a4526)]">{clean(phone)}</p>
      ) : null}
    </>
  )
}

function ShipFrom({ order }: { order: OrderWithLines }) {
  const name = clean(order.shipFromName) || clean(order.shipFromCompany)
  const company = clean(order.shipFromCompany)
  return (
    <Card
      icon={<FiMapPin className="h-3.5 w-3.5" />}
      title="Ship from"
      aside={order.shipFromResolved ? <span className="rounded-full bg-[var(--e-f4eede)] px-1.5 py-0.5 text-[9.5px] font-semibold uppercase tracking-wide text-[var(--e-6b5c42)]">Warehouse</span> : null}
    >
      <Address
        name={name}
        sub={company && company !== name ? company : null}
        lines={[order.shipFromAddr1, order.shipFromAddr2]}
        city={order.shipFromCity}
        state={order.shipFromState}
        zip={order.shipFromZip}
        country={order.shipFromCountryCd}
        phone={order.shipFromPhone}
      />
    </Card>
  )
}

function ShipTo({ order }: { order: OrderWithLines }) {
  // Some feeds (D2C totes) put a bare sequence number in ship_name — the
  // attention line is the real recipient then.
  const shipName = clean(order.shipName)
  const attn = clean(order.shipAttn)
  const name = (shipName && !/^\d+$/.test(shipName) ? shipName : attn) || 'Consignee'
  return (
    <Card icon={<FiMapPin className="h-3.5 w-3.5" />} title="Ship to">
      <Address
        name={name}
        sub={attn && attn !== name ? `Attn: ${attn}` : null}
        lines={[order.shipAddr1, order.shipAddr2]}
        city={order.shiptoCity}
        state={order.shiptoState}
        zip={order.shiptoZip}
        country={order.shiptoCountryCd}
        phone={order.phone}
      />
    </Card>
  )
}

function Shipment({ order, account }: { order: OrderWithLines; account: OrderWithLinesPayload['carrierAccount'] }) {
  const via = clean(order.shipviaCd)
  const resolved = clean(order.ndsResolvedShipviaCd)
  const unit = (order.weightUnit || 'LB').toLowerCase()
  return (
    <Card icon={<FiTruck className="h-3.5 w-3.5" />} title="Shipment">
      {account ? (
        <div className="mb-3 flex items-start gap-2.5 border-b border-[var(--e-f2ecdf)] pb-3">
          <span className="shrink-0 rounded-lg border border-[var(--e-efe7d6)] bg-white p-1.5">
            <CarrierLogo carrierId={account.carrierCode} size={16} className="rounded-sm" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-[13px] font-semibold text-[var(--e-1f150c)]">{account.carrierName || formatCarrierName(account.carrierCode)}</p>
            <p className="truncate text-[11.5px] text-[var(--e-6b5c42)]" title={account.accountNumber || undefined}>
              {[account.accountNumber, account.environment].filter(Boolean).join(' · ') || 'No account number'}
            </p>
            {account.accountCode && SCENARIO_LABEL[account.accountCode] ? (
              <span className="mt-1 inline-block rounded-full bg-sky-50 px-1.5 py-0.5 text-[10px] font-semibold text-sky-700">{SCENARIO_LABEL[account.accountCode]}</span>
            ) : null}
          </div>
        </div>
      ) : (
        <p className="mb-3 border-b border-[var(--e-f2ecdf)] pb-3 text-[12px] font-semibold text-amber-700">
          No carrier account resolved yet — set a company default or add carrier details.
        </p>
      )}
      <Rows rows={[
        ['Ship via', via && resolved && resolved !== via ? `${via} → ${resolved}` : via || resolved],
        ['Weight', order.weight != null ? `${order.weight} ${unit}` : null],
        ['Packages', order.packageCount && order.packageCount > 1 ? String(order.packageCount) : order.packageCount ? '1' : null],
        ['Declared value', money(order.declaredValue)],
        ['Goods', clean(order.goodsDesc)],
      ]} />
    </Card>
  )
}

function CustomsSection({ customs, order }: { customs: OrderCustoms | null; order: OrderWithLines }) {
  if (!customs) {
    return (
      <Card icon={<FiGlobe className="h-3.5 w-3.5" />} title="Customs">
        <p className="flex items-center gap-2 text-[12.5px] text-amber-800">
          <FiAlertTriangle className="h-4 w-4 shrink-0" />
          No customs declaration is recorded for this international order.
        </p>
      </Card>
    )
  }
  const currency = clean(customs.currency) || 'USD'
  const unit = (clean(customs.weightUnit) || clean(order.weightUnit) || 'LB').toLowerCase()
  const items = customs.items ?? []
  // HS codes are optional (D2C feeds never carry one) — the column shows only when one is recorded.
  const hasHs = items.some((it) => clean(it.hsCode))
  const totals = items.reduce(
    (t, it) => ({
      qty: t.qty + (it.quantity ?? 0),
      value: t.value + (it.quantity ?? 0) * (it.unitValue ?? 0),
      weight: t.weight + (it.weight ?? 0),
    }),
    { qty: 0, value: 0, weight: 0 },
  )
  const importer = customs.importer
  const importerLines = importer
    ? [importer.name, customs.importerCompany, importer.line1, importer.line2,
        [importer.city, [importer.state, importer.zip].filter(Boolean).join(' ')].filter(Boolean).join(', '),
        importer.country ? countryName(importer.country) : null, importer.phone].map(clean).filter(Boolean)
    : [clean(customs.importerCompany)].filter(Boolean)
  const ids = [
    customs.importerTaxId ? `Tax ID ${customs.importerTaxId}` : null,
    customs.importerVat ? `VAT ${customs.importerVat}` : null,
    customs.importerEori ? `EORI ${customs.importerEori}` : null,
  ].filter(Boolean)
  const reason = SHIPPING_PURPOSES.find((p) => p.value === customs.reasonForExport)?.label ?? clean(customs.reasonForExport)
  const duties = customs.dutiesPaidBy ? (DUTIES_LABEL[customs.dutiesPaidBy] ?? customs.dutiesPaidBy) : null

  return (
    <Card
      icon={<FiGlobe className="h-3.5 w-3.5" />}
      title="Customs"
      aside={<span className="text-[11px] text-[var(--e-a1906d)]">{items.length} item{items.length === 1 ? '' : 's'} · {currency}</span>}
    >
      <div className={`grid gap-x-6 gap-y-3 ${importerLines.length || ids.length ? 'md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_14rem]' : 'md:grid-cols-2'}`}>
        <Rows rows={[
          ['Incoterms', clean(customs.incoterms)],
          ['Reason', reason],
          ['Duties paid by', duties && customs.dutiesAccount ? `${duties} · ${customs.dutiesAccount}` : duties],
        ]} />
        <Rows rows={[
          ['FTR exemption', ftrExemptionLabel(customs.ftrExemption) || null],
          ['AES ITN', clean(customs.aesCitation), true],
          ['Export decl.', clean(customs.exportDeclarationReference), true],
        ]} />
        {importerLines.length || ids.length ? (
          <div className="min-w-0 rounded-lg bg-[var(--e-fcfaf5)] px-3 py-2 text-[12px] leading-relaxed text-[var(--e-3d2f1c)]">
            <p className="mb-0.5 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[var(--e-a1906d)]">Importer of record</p>
            {importerLines.map((l, i) => <p key={i} className={i === 0 ? 'font-semibold text-[var(--e-1f150c)]' : ''}>{l}</p>)}
            {ids.map((l) => <p key={l} className="font-mono text-[11px] text-[var(--e-6b5c42)]">{l}</p>)}
          </div>
        ) : null}
      </div>
      {clean(customs.notes) ? (
        <p className="mt-3 rounded-lg bg-[var(--e-fcfaf5)] px-3 py-2 text-[12px] text-[var(--e-5a4526)]"><span className="font-semibold">Notes: </span>{customs.notes}</p>
      ) : null}

      {items.length ? (
        <div className="mt-3.5 overflow-x-auto rounded-lg border border-[var(--e-efe7d6)]">
          <table className="w-full min-w-[640px] text-[12px] text-[var(--e-3d2f1c)]">
            <thead className="bg-[var(--e-fcfaf5)] text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[var(--e-8a7a5a)]">
              <tr>
                <th className="px-3 py-2">#</th>
                <th className="px-3 py-2">Description</th>
                {hasHs ? <th className="px-3 py-2">HS code</th> : null}
                <th className="px-3 py-2">Origin</th>
                <th className="px-3 py-2 text-right">Qty</th>
                <th className="px-3 py-2 text-right">Unit value</th>
                <th className="px-3 py-2 text-right">Weight</th>
                <th className="px-3 py-2 text-right">Total</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--e-f2ecdf)]">
              {items.map((it, i) => (
                <tr key={it.id ?? i} className="align-top">
                  <td className="px-3 py-2 text-[var(--e-a1906d)]">{i + 1}</td>
                  <td className="px-3 py-2">
                    <span className="font-medium text-[var(--e-1f150c)]">{it.description || '—'}</span>
                    {it.sku ? <span className="block font-mono text-[10.5px] text-[var(--e-a1906d)]">SKU {it.sku}</span> : null}
                  </td>
                  {hasHs ? <td className="px-3 py-2 font-mono text-[11px]">{it.hsCode || <span className="text-[var(--e-cdbf9f)]">—</span>}</td> : null}
                  <td className="px-3 py-2">{it.countryOfOrigin || <span className="text-[var(--e-cdbf9f)]">—</span>}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{it.quantity}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(it.unitValue, currency) ?? '—'}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{it.weight != null ? `${it.weight} ${unit}` : '—'}</td>
                  <td className="px-3 py-2 text-right font-semibold tabular-nums text-[var(--e-1f150c)]">{money((it.quantity ?? 0) * (it.unitValue ?? 0), currency)}</td>
                </tr>
              ))}
            </tbody>
            {items.length > 1 ? (
              <tfoot className="border-t border-[var(--e-efe7d6)] bg-[var(--e-fcfaf5)] font-semibold text-[var(--e-1f150c)]">
                <tr>
                  <td className="px-3 py-2" colSpan={hasHs ? 4 : 3}>Total</td>
                  <td className="px-3 py-2 text-right tabular-nums">{totals.qty}</td>
                  <td />
                  <td className="px-3 py-2 text-right tabular-nums">{`${Number(totals.weight.toFixed(3))} ${unit}`}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(totals.value, currency)}</td>
                </tr>
              </tfoot>
            ) : null}
          </table>
        </div>
      ) : (
        <p className="mt-3 text-[12px] text-amber-800">The declaration has no commodity lines.</p>
      )}
    </Card>
  )
}

function OrderLinesSection({ order }: { order: OrderWithLines }) {
  const lines = order.orderLines
  const hasHs = lines.some((l) => clean(l.hsCode))
  const totals = {
    qty: lines.reduce((s, l) => s + (l.qtyShipped ?? 0), 0),
    value: lines.reduce((s, l) => s + (l.totalPrice ?? 0), 0),
    customs: lines.reduce((s, l) => s + (l.customsDeclValue ?? 0), 0),
  }
  return (
    <Card icon={<FiList className="h-3.5 w-3.5" />} title="Order lines" aside={<span className="text-[11px] text-[var(--e-a1906d)]">{lines.length} line{lines.length === 1 ? '' : 's'}</span>}>
      <div className="overflow-x-auto rounded-lg border border-[var(--e-efe7d6)]">
        <table className="w-full min-w-[640px] text-[12px] text-[var(--e-3d2f1c)]">
          <thead className="bg-[var(--e-fcfaf5)] text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[var(--e-8a7a5a)]">
            <tr>
              <th className="px-3 py-2">#</th>
              <th className="px-3 py-2">Item</th>
              <th className="px-3 py-2">Description</th>
              {hasHs ? <th className="px-3 py-2">HS code</th> : null}
              <th className="px-3 py-2">Origin</th>
              <th className="px-3 py-2 text-right">Qty</th>
              <th className="px-3 py-2 text-right">Unit</th>
              <th className="px-3 py-2 text-right">Total</th>
              <th className="px-3 py-2 text-right">Customs</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--e-f2ecdf)]">
            {lines.map((line) => (
              <tr key={line.id} className="align-top">
                <td className="px-3 py-2 text-[var(--e-a1906d)]">{line.lineNo}</td>
                <td className="px-3 py-2 font-mono text-[11px]">{line.itemNo || '—'}</td>
                <td className="px-3 py-2">
                  <span className="font-medium text-[var(--e-1f150c)]">{line.itemDescription || line.description || '—'}</span>
                  {line.hsDesc ? <span className="block text-[10.5px] text-[var(--e-a1906d)]">{line.hsDesc}</span> : null}
                </td>
                {hasHs ? <td className="px-3 py-2 font-mono text-[11px]">{line.hsCode || '—'}</td> : null}
                <td className="px-3 py-2">{line.countryOfOrigin || '—'}</td>
                <td className="px-3 py-2 text-right tabular-nums">{line.qtyShipped ?? '—'}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(line.unitPrice) ?? '—'}</td>
                <td className="px-3 py-2 text-right font-semibold tabular-nums text-[var(--e-1f150c)]">{money(line.totalPrice) ?? '—'}</td>
                <td className="px-3 py-2 text-right tabular-nums text-[var(--e-6b5c42)]">{money(line.customsDeclValue) ?? '—'}</td>
              </tr>
            ))}
          </tbody>
          {lines.length > 1 ? (
            <tfoot className="border-t border-[var(--e-efe7d6)] bg-[var(--e-fcfaf5)] font-semibold text-[var(--e-1f150c)]">
              <tr>
                <td className="px-3 py-2" colSpan={hasHs ? 5 : 4}>Total</td>
                <td className="px-3 py-2 text-right tabular-nums">{totals.qty}</td>
                <td />
                <td className="px-3 py-2 text-right tabular-nums">{money(totals.value)}</td>
                <td className="px-3 py-2 text-right tabular-nums text-[var(--e-6b5c42)]">{money(totals.customs)}</td>
              </tr>
            </tfoot>
          ) : null}
        </table>
      </div>
    </Card>
  )
}

function PackagesSection({ order }: { order: OrderWithLines }) {
  const packages = order.packages ?? []
  return (
    <Card icon={<FiPackage className="h-3.5 w-3.5" />} title="Packages" aside={<span className="text-[11px] text-[var(--e-a1906d)]">{packages.length} pieces</span>}>
      <div className="overflow-x-auto rounded-lg border border-[var(--e-efe7d6)]">
        <table className="w-full text-[12px] text-[var(--e-3d2f1c)]">
          <thead className="bg-[var(--e-fcfaf5)] text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[var(--e-8a7a5a)]">
            <tr>
              <th className="px-3 py-2">#</th>
              <th className="px-3 py-2">Tracking</th>
              <th className="px-3 py-2 text-right">Weight</th>
              <th className="px-3 py-2 text-right">Dimensions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--e-f2ecdf)]">
            {packages.map((p, i) => (
              <tr key={p.sequenceNumber ?? i}>
                <td className="px-3 py-2 text-[var(--e-a1906d)]">{p.sequenceNumber ?? i + 1}</td>
                <td className="px-3 py-2 font-mono text-[11px]">
                  {p.trackingUrl && p.trackingNumber
                    ? <a href={p.trackingUrl} target="_blank" rel="noreferrer" className="underline decoration-[var(--e-cdbf9f)] underline-offset-2 hover:text-[var(--e-1f150c)]">{p.trackingNumber}</a>
                    : p.trackingNumber || '—'}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{p.weight != null ? `${p.weight} ${(p.weightUnit || 'LB').toLowerCase()}` : '—'}</td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {p.length && p.width && p.height ? `${p.length} × ${p.width} × ${p.height} ${(p.dimUnit || 'IN').toLowerCase()}` : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}
