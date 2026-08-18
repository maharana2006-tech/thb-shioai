import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiActivity, FiArrowLeft, FiCopy, FiDownload, FiExternalLink, FiFileText, FiPrinter, FiTag } from 'react-icons/fi'
import { orderService, type LabelDocumentPayload } from '../api/orderService'
import TrackingTimelineModal from './tracking/TrackingTimelineModal'
import { useAppSession } from '../hooks/useAppSession'
import { getTenantIdForUser, normalizeRole } from '../utils/roles'
import { encodeCode128B } from '../utils/code128'
import { formatCarrierName, normalizeCarrierCode } from '../utils/carrierUtils'

type DocumentTab = 'label' | 'invoice'

const CARRIER_THEMES: Record<string, { band: string; accent: string; serviceLetter: string }> = {
  ups: { band: '#351C15', accent: '#F5C242', serviceLetter: 'G' },
  fedex: { band: '#4D148C', accent: '#FF6600', serviceLetter: 'E' },
  usps: { band: '#1F5AA6', accent: '#D21F2B', serviceLetter: 'P' },
}

const fallbackTheme = { band: '#0f172a', accent: '#94a3b8', serviceLetter: 'S' }

const formatDate = (value?: string | null) => {
  if (!value) {
    return '—'
  }

  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleDateString('en-US', { day: '2-digit', month: 'short', year: 'numeric' })
}

/** Carrier label date style: 12JUN26 */
const labelDate = (value?: string | null) => {
  const parsed = value ? new Date(value) : new Date()
  const d = Number.isNaN(parsed.getTime()) ? new Date() : parsed
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  return `${String(d.getDate()).padStart(2, '0')}${months[d.getMonth()]}${String(d.getFullYear()).slice(-2)}`
}

const money = (value: number | null | undefined) => (typeof value === 'number' ? value.toFixed(2) : '0.00')

/** Real Code 128B barcode rendered as SVG — same symbology carriers print. */
function Barcode128({ value, height = 64 }: { value: string; height?: number }) {
  const encoded = useMemo(() => encodeCode128B(value), [value])

  if (!encoded) {
    return null
  }

  const quietZone = 10

  return (
    <svg
      viewBox={`0 0 ${encoded.totalWidth + quietZone * 2} ${height}`}
      className="h-16 w-full"
      preserveAspectRatio="none"
      aria-label={`Barcode ${value}`}
    >
      <rect width={encoded.totalWidth + quietZone * 2} height={height} fill="white" />
      {encoded.bars.map((bar, index) => (
        <rect key={index} x={bar.x + quietZone} y={0} width={bar.width} height={height} fill="black" />
      ))}
    </svg>
  )
}

/** Deterministic PDF417-style stacked symbol — the 2D barcode block FedEx labels carry. */
function Pdf417Symbol({ seed }: { seed: string }) {
  const rows = useMemo(() => {
    let hash = 7
    for (let i = 0; i < seed.length; i += 1) {
      hash = (hash * 31 + seed.charCodeAt(i)) % 104729
    }

    const grid: number[][] = []
    for (let r = 0; r < 16; r += 1) {
      const row: number[] = []
      for (let c = 0; c < 64; c += 1) {
        hash = (hash * 1103515245 + 12345) % 2147483648
        row.push(hash % 100 < 52 ? 1 : 0)
      }
      grid.push(row)
    }
    return grid
  }, [seed])

  return (
    <svg viewBox="0 0 300 130" className="h-full w-full" preserveAspectRatio="none" aria-hidden="true">
      <rect width="300" height="130" fill="white" />
      {/* start / stop patterns */}
      <rect x="0" y="0" width="7" height="130" fill="black" />
      <rect x="10" y="0" width="2" height="130" fill="black" />
      <rect x="14" y="0" width="4" height="130" fill="black" />
      <rect x="282" y="0" width="4" height="130" fill="black" />
      <rect x="289" y="0" width="2" height="130" fill="black" />
      <rect x="294" y="0" width="6" height="130" fill="black" />
      {rows.map((row, r) =>
        row.map((cell, c) =>
          cell ? (
            <rect key={`${r}-${c}`} x={22 + c * 4} y={r * 8.1} width={3} height={7} fill="black" />
          ) : null
        )
      )}
    </svg>
  )
}

/** Deterministic base-36 chunk for carrier form/routing codes. */
const hash36 = (value: string, length: number) => {
  let hash = 7
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash * 33 + value.charCodeAt(i)) % 2147483647
  }
  return hash.toString(36).toUpperCase().padStart(length, '0').slice(0, length)
}

export default function LabelDocumentPage() {
  const { orderNo: orderNoParam } = useParams()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { role, username } = useAppSession()
  const orderNo = Number(orderNoParam)

  const [payload, setPayload] = useState<LabelDocumentPayload | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState<DocumentTab>('label')
  const [trackingOpen, setTrackingOpen] = useState(false)
  const [zplBusy, setZplBusy] = useState(false)

  const fetchZpl = async (): Promise<string | null> => {
    setZplBusy(true)
    try {
      // Audit L1 — pass pkgIndex through so the ZPL matches the on-screen
      // package view. Pre-fix, all downloads returned pkg-1 ZPL regardless
      // of the multi-package picker selection.
      return await orderService.getLabelZpl(orderNo, pkgIndex)
    } catch (err) {
      notify.apiError(err, 'Failed to fetch the ZPL label.')
      return null
    } finally {
      setZplBusy(false)
    }
  }

  const downloadZpl = async () => {
    const zpl = await fetchZpl()
    if (!zpl) return

    const blob = new Blob([zpl], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    // Audit L1 — filename encodes pkg N of M for multi-package shipments
    // so operators can tell downloads apart when they save several to
    // disk before printing.
    link.download = pkgCount > 1
      ? `label-${orderNo}-pkg${pkgIndex}of${pkgCount}.zpl`
      : `label-${orderNo}.zpl`
    link.click()
    URL.revokeObjectURL(url)
    notify.success(`${link.download} downloaded — send it straight to a Zebra printer.`)
  }

  const copyZpl = async () => {
    const zpl = await fetchZpl()
    if (!zpl) return

    try {
      await navigator.clipboard.writeText(zpl)
      notify.success('ZPL copied — paste into labelary.com/viewer to preview the thermal print.')
    } catch {
      notify.error('Clipboard is blocked in this browser — use Download .zpl instead.')
    }
  }

  useEffect(() => {
    if (!Number.isFinite(orderNo)) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- surface invalid-order state before any async fetch; guard against non-numeric URL segments
      setError('Invalid order number.')
      setLoading(false)
      return
    }

    let cancelled = false
    setLoading(true)
    setError(null)

    orderService
      .getLabelDocument(orderNo)
      .then((response) => {
        if (!cancelled) {
          setPayload(response.data)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load label document.')
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [orderNo])

  const order = payload?.order
  const label = payload?.label
  const shipper = payload?.shipper
  const resolution = payload?.resolution
  const legacyAccount = payload?.carrierAccount
  // Customs blocks resolved from the client's Importer/Broker profile.
  const importer = payload?.importer ?? null
  const broker = payload?.broker ?? null
  const brokerage = payload?.brokerage ?? null
  const customsDefaults = payload?.customsDefaults ?? null
  const receiverIsImporter = !importer || importer.type === 'RECEIVER'

  // TENANT users may only open their own orders.
  const ownTenant = getTenantIdForUser(normalizeRole(role), username)
  const tenantBlocked = Boolean(ownTenant && order && (order.tenantId || order.custNo)?.toUpperCase() !== ownTenant)

  // Account details from the cascade resolution (falls back to legacy payloads).
  const accountCarrierCode = resolution?.carrierCode || legacyAccount?.carrierCode || order?.shipviaCd || null
  const accountNumber = resolution?.accountNumber || legacyAccount?.accountNumber || null
  const accountName = resolution?.accountName || legacyAccount?.carrierName || null
  const environment = (resolution?.environment || legacyAccount?.environment || 'SANDBOX').toUpperCase()
  const isSandbox = environment !== 'PRODUCTION'

  const carrierId = normalizeCarrierCode(accountCarrierCode)
  const theme = (carrierId && CARRIER_THEMES[carrierId]) || fallbackTheme
  const carrierDisplay = formatCarrierName(accountCarrierCode)
  const shipDate = label?.generatedAt || order?.createdDate

  // Commercial-invoice lines: prefer the per-order customs items entered against
  // the order (manual/international shipments); fall back to the ERP order lines.
  const customsItems = payload?.customs?.items ?? []
  const lines =
    customsItems.length > 0
      ? customsItems.map((it, i) => {
          const qty = it.quantity ?? 1
          const unit = it.unitValue ?? 0
          return {
            id: i,
            lineNo: i + 1,
            itemNo: it.sku ?? null,
            itemDescription: it.description ?? null,
            description: it.description ?? null,
            hsCode: it.hsCode ?? null,
            countryOfOrigin: it.countryOfOrigin ?? null,
            qtyShipped: qty,
            unitPrice: unit,
            totalPrice: qty * unit,
            customsDeclValue: qty * unit,
          }
        })
      : order?.orderLines || []
  const invoiceTotal = lines.reduce((sum, line) => sum + (line.totalPrice ?? 0), 0)
  const customsTotal = lines.reduce((sum, line) => sum + (line.customsDeclValue ?? 0), 0)
  const totalQty = lines.reduce((sum, line) => sum + (line.qtyShipped ?? 0), 0)

  // Some order feeds put a bare sequence digit in ship_name; prefer a plausible name.
  // Never falls back to custNo (client code) — that would render the tenant
  // identifier as the parcel's addressee. Placeholder is a literal '-' so
  // mis-populated shipments are visibly broken.
  const rawShipName = order?.shipName?.trim()
  const recipientName =
    (rawShipName && rawShipName.length > 2 ? rawShipName : order?.shipAttn) || '-'
  const recipientCityLine = order
    ? `${order.shiptoCity || ''}, ${order.shiptoState || ''} ${order.shiptoZip || ''}`.trim()
    : ''
  const destCountry = (order?.shiptoCountryCd || 'US').toUpperCase()
  // Sprint 48 B10 - customer-facing order number. Backend now sends
  // displayOrderNo pre-formatted (e.g. "MAN900001" for manual shipments,
  // just the number for ERP-imported orders). Fall back to the raw integer
  // when the payload predates this field.
  const orderDisplay = (order as { displayOrderNo?: string })?.displayOrderNo
    || (order?.orderNo != null ? String(order.orderNo) : '')
  // Cross-border flag from the backend (same-customs-territory pairs like
  // intra-EU are treated as domestic). Domestic shipments suppress
  // incoterms, EEI text, and the destination-country tag on the label.
  const isInternational = Boolean(payload?.international)
  const originCountry = (shipper?.countryCode || '').toUpperCase()
  const isUsExport = isInternational && originCountry === 'US'
  // Multi-package: total M comes from the order's package_count column;
  // current N comes from the ?pkg= query param (default 1). Clamped.
  const pkgCount = Math.max(1, Number(order?.packageCount) || 1)
  const rawPkg = Number(searchParams.get('pkg')) || 1
  const pkgIndex = Math.min(Math.max(1, rawPkg), pkgCount)
  // Per-package row for the currently-selected box (drives per-pkg tracking,
  // weight, dims). Null / undefined when the order predates label_package
  // persistence — every downstream read falls back to the shipment-level
  // label + order fields.
  const perPkg = Array.isArray(order?.packages)
    ? order.packages.find((p) => p?.sequenceNumber === pkgIndex) ?? null
    : null
  const trackingNumber = perPkg?.trackingNumber || label?.trackingNumber || null
  const generated = Boolean(label?.isGenerated && trackingNumber)
  const perPkgWeight = perPkg?.weight ?? null
  const perPkgWeightUnit = perPkg?.weightUnit || null

  // ---- carrier-form codes, derived deterministically like the real label carries ----
  const formCode = `${hash36(`${orderNo}${order?.shiptoZip || ''}`, 5)}/${hash36(`${order?.shiptoZip || ''}${orderNo}`, 4)}/${hash36(`${order?.custNo || ''}${orderNo}`, 4)}`
  const meterCode = `J${String(orderNo).padStart(9, '0')}${(order?.shiptoZip || '000').slice(0, 3)}uv`
  const ursaCode = `XQ ${(order?.shiptoCity || 'DEST').replace(/[^A-Za-z]/g, '').slice(0, 4).toUpperCase()}`
  const airportCode = (order?.shiptoCity || 'DST').replace(/[^A-Za-z]/g, '').slice(0, 3).toUpperCase()
  // Service display comes from the resolved catalog service (Settings →
  // Shipping Services via the ERP ship-via mapping); the per-carrier literals
  // are only the fallback for unmapped codes. Guard: an order can ship on a
  // DIFFERENT carrier than its ship-via suggests (manual account pick) — only
  // trust the mapping when the carriers agree.
  const resolvedService =
    payload?.service && normalizeCarrierCode(payload.service.carrier) === carrierId ? payload.service : null
  const serviceCodeBig = resolvedService
    ? resolvedService.code.replace(/_/g, ' ').slice(0, 8)
    : carrierId === 'fedex' ? 'IP EOD' : carrierId === 'ups' ? 'GND' : 'PRI'
  const serviceTier = resolvedService
    ? resolvedService.name.replace(/^(UPS|FedEx|USPS)\s+/i, '')
    : carrierId === 'fedex' ? 'Express' : carrierId === 'usps' ? 'Priority' : 'Ground'
  const wordmark = carrierId === 'fedex' ? 'FedEx' : carrierDisplay
  const trackingDigits = (trackingNumber || '').replace(/[^0-9]/g, '').padEnd(12, '0').slice(0, 12)
  const trkGrouped = `${trackingDigits.slice(0, 4)} ${trackingDigits.slice(4, 8)} ${trackingDigits.slice(8, 12)}`
  const numericLine = `${trackingDigits.slice(0, 4)} ${trackingDigits.slice(4, 8)} ${trackingDigits.slice(8, 9)} (${trackingDigits.slice(0, 3)} ${trackingDigits.slice(3, 6)} ${trackingDigits.slice(6, 10)}) 0 00 ${trackingDigits.slice(0, 4)} ${trackingDigits.slice(4, 8)} ${trackingDigits.slice(8, 12)}`

  return (
    <div className="space-y-4">
      {/* Per-document page size: 4x6 for the label, Letter for the invoice. */}
      <style>{tab === 'label'
        ? '@page { size: 4in 6in; margin: 0.12in; }'
        : '@page { size: letter; margin: 0.5in; }'}</style>

      <div className="flex flex-wrap items-center justify-between gap-3 print:hidden">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiArrowLeft className="h-3.5 w-3.5" />
            Back
          </button>
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400">Label Documents</p>
            <h2 className="flex items-center gap-2 text-base font-semibold text-slate-950">
              Order #{orderNo}
              {payload?.isReturn ? (
                <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.12em] text-amber-700">
                  Return label
                </span>
              ) : null}
              {order?.tenantId ? <span className="text-xs font-medium text-slate-500">Tenant {order.tenantId}</span> : null}
            </h2>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="inline-flex rounded-xl border border-slate-200 bg-slate-50 p-1">
            <button
              type="button"
              onClick={() => setTab('label')}
              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[12px] font-semibold transition ${
                tab === 'label' ? 'bg-slate-950 text-white' : 'text-slate-600 hover:text-slate-950'
              }`}
            >
              <FiTag className="h-3 w-3" />
              Shipping Label
            </button>
            <button
              type="button"
              onClick={() => setTab('invoice')}
              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[12px] font-semibold transition ${
                tab === 'invoice' ? 'bg-slate-950 text-white' : 'text-slate-600 hover:text-slate-950'
              }`}
            >
              <FiFileText className="h-3 w-3" />
              Commercial Invoice
            </button>
          </div>

          {/* Multi-package picker — only shown when the shipment has >1 box. */}
          {pkgCount > 1 ? (
            <div className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-2 py-1 text-[12px] text-slate-700 print:hidden">
              <span className="font-semibold uppercase tracking-wide">Package</span>
              <select
                value={pkgIndex}
                onChange={(e) => {
                  const next = new URLSearchParams(searchParams)
                  next.set('pkg', e.target.value)
                  setSearchParams(next, { replace: true })
                }}
                className="rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 text-[12px] font-semibold focus:outline-none focus:ring-1 focus:ring-slate-400"
              >
                {Array.from({ length: pkgCount }, (_, i) => i + 1).map((n) => (
                  <option key={n} value={n}>{n} of {pkgCount}</option>
                ))}
              </select>
            </div>
          ) : null}

          {trackingNumber ? (
            <button
              type="button"
              onClick={() => setTrackingOpen(true)}
              title="Open the live tracking timeline for this order"
              className="inline-flex items-center gap-1.5 rounded-xl border border-emerald-600 bg-white px-3 py-1.5 text-[13px] font-semibold text-emerald-700 transition hover:bg-emerald-50 shadow-sm"
            >
              <FiActivity className="h-3.5 w-3.5" />
              Track live
            </button>
          ) : label?.trackingUrl ? (
            <a
              href={label.trackingUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 transition hover:bg-slate-50"
            >
              <FiExternalLink className="h-3.5 w-3.5" />
              Track
            </a>
          ) : null}

          {tab === 'label' ? (
            <>
              <button
                type="button"
                onClick={() => {
                  void downloadZpl()
                }}
                disabled={loading || Boolean(error) || tenantBlocked || zplBusy}
                title="Raw Zebra thermal-printer commands (4x6 @ 203 dpi)"
                className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiDownload className="h-3.5 w-3.5" />
                {zplBusy ? 'Fetching…' : 'Download .zpl'}
              </button>
              <button
                type="button"
                onClick={() => {
                  void copyZpl()
                }}
                disabled={loading || Boolean(error) || tenantBlocked || zplBusy}
                title="Copy ZPL to preview on labelary.com/viewer"
                className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiCopy className="h-3.5 w-3.5" />
                Copy ZPL
              </button>
            </>
          ) : null}

          <button
            type="button"
            onClick={() => window.print()}
            disabled={loading || Boolean(error) || tenantBlocked}
            className="inline-flex items-center gap-1.5 rounded-xl bg-slate-950 px-3.5 py-1.5 text-[13px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            <FiPrinter className="h-3.5 w-3.5" />
            Print {tab === 'label' ? 'Label' : 'Invoice'}
          </button>
        </div>
      </div>

      {loading ? (
        <div className="rounded-[26px] border border-slate-200/80 bg-white/90 px-4 py-16 text-center text-sm text-slate-500 shadow-sm">
          Loading label document...
        </div>
      ) : error ? (
        <div className="rounded-[26px] border border-rose-200 bg-rose-50 px-4 py-10 text-center text-sm font-semibold text-rose-700">
          {error} — <Link to="/orders" className="underline">back to orders</Link>
        </div>
      ) : tenantBlocked ? (
        <div className="rounded-[26px] border border-amber-200 bg-amber-50 px-4 py-10 text-center text-sm font-semibold text-amber-800">
          This order belongs to another tenant.
        </div>
      ) : order ? (
        <div className="flex justify-center rounded-[26px] border border-slate-200/80 bg-slate-100/70 p-6 shadow-inner print:border-0 print:bg-white print:p-0 print:shadow-none">
          {tab === 'label' ? (
            /* ==================== 4x6 SHIPPING LABEL (real carrier anatomy) ==================== */
            <div
              className="print-doc relative w-[430px] shrink-0 border border-slate-300 bg-white text-black shadow-xl print:w-[3.76in] print:border-0 print:shadow-none"
              style={{ fontFamily: '"Helvetica Neue", Helvetica, Arial, sans-serif' }}
            >
              {!generated ? (
                <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center">
                  <span className="-rotate-[24deg] border-4 border-red-500/70 px-6 py-2 text-2xl font-black tracking-widest text-red-500/70">
                    NOT GENERATED
                  </span>
                </div>
              ) : null}

              {/* ---- header: (incoterms) + two-column origin / ship data ---- */}
              <div className="px-2 pb-1 pt-1.5">
                {/* Incoterms are a cross-border commercial term — hidden
                    for domestic parcels. */}
                {isInternational ? (
                  <p className="text-[11px] font-black leading-none">
                    INCOTERMS: {customsDefaults?.incoterms || (receiverIsImporter ? 'DAP' : 'DDP')}
                  </p>
                ) : null}
                <div className="mt-1 grid grid-cols-[1.08fr_1fr] font-mono text-[10px] leading-[13px]">
                  <div className="pr-2 uppercase">
                    <p>ORIGIN ID:{(shipper?.state || 'XX').toUpperCase()}{(shipper?.postalCode || '').slice(0, 2)}A&nbsp;&nbsp;{(shipper?.phone || '').replace(/\s+/g, '')}</p>
                    <p>{shipper?.name}</p>
                    <p>{shipper?.addressLine1}</p>
                    <p>&nbsp;</p>
                    <p>{shipper?.city}, {shipper?.state} {shipper?.postalCode} {shipper?.countryCode}</p>
                    <p>SIGN: {shipper?.name}</p>
                  </div>
                  <div className="border-l border-black pl-2">
                    <p>SHIP DATE: {labelDate(shipDate)}</p>
                    {/* The label carries the weight sent to the carrier: BILLABLE
                        (max of actual+tare and DIM weight), not the raw scale weight. */}
                    <p>
                      ACTWGT: {(() => {
                        // Per-package weight when a label_package row exists;
                        // else fall back to the billable weight from the preset,
                        // else the raw order weight.
                        const w = perPkgWeight
                          ?? (payload?.packagePreset as { billableWeight?: number | null } | null | undefined)?.billableWeight
                          ?? order.weight
                        return typeof w === 'number' ? w.toFixed(2) : '—'
                      })()} {(perPkgWeightUnit || 'KG').toUpperCase()}
                    </p>
                    <p>CAD: {orderDisplay}{pkgCount > 1 ? `-${pkgIndex}` : ''}/MSHIP1</p>
                    <p>&nbsp;</p>
                    <p>BILL SENDER</p>
                    {/* EEI exemption text is US-export-specific. */}
                    {isUsExport ? (
                      <p className="text-[9px] font-black" style={{ fontFamily: 'inherit' }}>NO EEI 30.37(a)</p>
                    ) : null}
                  </div>
                </div>
              </div>
              <div className="mx-1 h-[3px] bg-black" />

              {/* ---- TO block + rotated form code on the right edge ---- */}
              <div className="relative px-2 py-1">
                <p
                  className="absolute right-0.5 top-5 text-[9px] font-bold tracking-wide"
                  style={{ writingMode: 'vertical-rl' }}
                >
                  {formCode}
                </p>
                <div className="flex gap-1 pr-5">
                  <span className="pt-1 text-[10px] font-black">TO</span>
                  <div className="min-w-0">
                    <p className="truncate text-[21px] font-black uppercase leading-[24px] tracking-tight">{recipientName}</p>
                    {/* custNo used to render here as a big uppercase line — read as part
                        of the address. Moved to the warehouse footer below the label. */}
                    {order.shipAddr1 ? (
                      <p className="text-[18px] font-black uppercase leading-[22px]">{order.shipAddr1}</p>
                    ) : null}
                    {generated && isSandbox ? (
                      <p className="text-[18px] font-black leading-[22px]">**TEST LABEL - DO NOT SHIP**</p>
                    ) : null}
                    <div className="flex items-end justify-between">
                      <p className="text-[20px] font-black uppercase leading-[24px]">
                        {order.shiptoCity} {order.shiptoState} {order.shiptoZip}
                      </p>
                      {/* Destination country tag: only show when the parcel
                          actually crosses a customs boundary. Domestic
                          parcels don't need it (country is implicit from the
                          rest of the label). */}
                      {isInternational ? (
                        <p className="text-[20px] font-black leading-[24px]">({destCountry})</p>
                      ) : null}
                    </div>
                  </div>
                </div>
                <p className="mt-0.5 text-[11px] font-bold leading-[13px]">{order.phone || ''}</p>
                <div className="grid grid-cols-2 pr-8 text-[9px] font-bold leading-[12px]">
                  <span>INV:</span>
                  <span>REF: {orderDisplay}</span>
                  {/* PO/DEPT row removed — PO isn't captured on the order yet, and
                      DEPT was re-rendering the client code inside the TO block.
                      Client code now lives in the warehouse footer below. */}
                </div>
              </div>
              <div className="mx-1 h-px bg-black" />

              {/* ---- PDF417 block + carrier wordmark / service letter / meter ---- */}
              <div className="flex items-stretch gap-1.5 px-2 py-1.5">
                <div className="h-[122px] min-w-0 flex-1">
                  {generated ? (
                    <Pdf417Symbol seed={`${trackingNumber}:${order.orderNo}`} />
                  ) : (
                    <div className="flex h-full items-center justify-center border border-dashed border-slate-400 text-[10px] text-slate-500">
                      2D BARCODE AFTER GENERATION
                    </div>
                  )}
                </div>
                <div className="relative flex w-[96px] flex-col items-end pr-3">
                  <p className="text-[30px] font-black italic leading-[26px] tracking-tighter">{wordmark}</p>
                  <p className="text-[13px] font-black leading-[15px]">{serviceTier}</p>
                  <div className="mt-1 border-[3.5px] border-black px-1.5 leading-none">
                    <span className="text-[46px] font-black leading-[50px]">{theme.serviceLetter}</span>
                  </div>
                  <p
                    className="absolute -right-0.5 bottom-0 text-[8px] font-bold"
                    style={{ writingMode: 'vertical-rl' }}
                  >
                    {meterCode}
                  </p>
                </div>
              </div>
              <div className="mx-1 h-px bg-black" />

              {/* ---- routing section ---- */}
              <div className="px-2 pt-0.5">
                <p className="pr-1 text-right text-[15px] font-black leading-[16px]">A1</p>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[11px] font-black">TRK#</span>
                    <span className="border border-black px-0.5 text-[10px] font-bold leading-[14px]">0430</span>
                    <span className="text-[19px] font-black tracking-wider">{generated ? trkGrouped : 'PENDING'}</span>
                  </div>
                  <p className="text-[19px] font-black">{serviceCodeBig}</p>
                </div>
                <div className="mt-0.5 flex items-end justify-between">
                  <p className="text-[44px] font-black leading-[40px] tracking-tight">{ursaCode}</p>
                  <div className="text-right">
                    <p className="text-[21px] font-black leading-[19px]">{order.shiptoZip}</p>
                    <p className="leading-[20px]">
                      <span className="mr-1 align-middle text-[11px] font-bold">{order.shiptoState}–{destCountry}</span>
                      <span className="text-[21px] font-black">{airportCode}</span>
                    </p>
                  </div>
                </div>
                {generated ? (
                  <p className="mt-0.5 text-[12px] font-bold tracking-wide">{numericLine}</p>
                ) : null}
              </div>

              {/* ---- warehouse footer (mirrors ZPL renderer) ---- */}
              <div className="mx-1 h-px bg-black" />
              <div className="px-2 py-1 text-[9px] font-bold uppercase leading-[12px] tracking-wide">
                <p>
                  CLIENT: {order.custNo || '-'} · ORDER: {orderDisplay}
                  {order.orderSuffix ? `-${order.orderSuffix}` : ''}
                  {` · PKG ${pkgIndex} OF ${pkgCount} · `}
                  {formatDate(shipDate)}
                </p>
                <p>
                  SVC: {serviceTier} ({order.shipviaCd || '-'}) · WT:{' '}
                  {(perPkgWeight ?? order.weight) ?? '-'} {(perPkgWeightUnit || order.weightUnit || 'KG').toUpperCase()}
                  {order.tenantId ? ` · TENANT: ${order.tenantId}` : ''}
                </p>
              </div>

              {/* ---- bottom Code 128 ---- */}
              <div className="relative px-3 pb-2.5 pt-1">
                {generated && trackingNumber ? (
                  <>
                    <Barcode128 value={trackingNumber} height={96} />
                    {isSandbox ? (
                      <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
                        <span
                          className="text-[56px] font-black tracking-[0.08em]"
                          style={{ WebkitTextStroke: '10px white' }}
                          aria-hidden="true"
                        >
                          SAMPLE
                        </span>
                        <span className="absolute text-[56px] font-black tracking-[0.08em]">SAMPLE</span>
                      </div>
                    ) : null}
                  </>
                ) : (
                  <div className="flex h-20 items-center justify-center border border-dashed border-slate-400 text-[10px] text-slate-500">
                    BARCODE AVAILABLE AFTER LABEL GENERATION
                  </div>
                )}
              </div>
            </div>
          ) : (
            /* ==================== COMMERCIAL INVOICE (Letter) ==================== */
            <div className="print-doc w-full max-w-[820px] bg-white p-8 text-slate-900 shadow-xl print:max-w-none print:p-0 print:shadow-none">
              <div className="flex items-start justify-between border-b-2 border-slate-900 pb-4">
                <div>
                  <h1 className="text-2xl font-black tracking-tight">COMMERCIAL INVOICE</h1>
                  <p className="mt-1 text-xs text-slate-500">International shipping document — customs declaration</p>
                  {accountNumber ? (
                    <p className="mt-1 font-mono text-[11px] text-slate-600">
                      {accountName || carrierDisplay} — {trackingNumber || `ORDER ${orderDisplay}`}
                    </p>
                  ) : null}
                </div>
                <div className="text-right text-xs leading-5">
                  <p><span className="font-semibold">Ship Date:</span> {formatDate(shipDate)}</p>
                  <p><span className="font-semibold">International Tracking#:</span> {trackingNumber || 'Pending'}</p>
                  <p><span className="font-semibold">Purpose:</span> {(customsDefaults?.reasonForExport || 'COMMERCIAL / SOLD').toUpperCase()}</p>
                  <p><span className="font-semibold">Nbr pkgs:</span> 1</p>
                  <p><span className="font-semibold">Invoice #:</span> INV-{orderDisplay}</p>
                </div>
              </div>

              <div className="mt-4 grid grid-cols-2 gap-4 text-xs">
                <div className="border border-slate-300 p-3">
                  <p className="text-[10px] font-bold uppercase tracking-widest text-slate-500">Shipper / Exporter</p>
                  <p className="mt-1.5 font-bold">{shipper?.name}</p>
                  <p>{shipper?.addressLine1}</p>
                  <p>
                    {shipper?.city}, {shipper?.state} {shipper?.postalCode}, {shipper?.countryCode}
                  </p>
                  <p>PH: {shipper?.phone}</p>
                </div>
                <div className="border border-slate-300 p-3">
                  <p className="text-[10px] font-bold uppercase tracking-widest text-slate-500">Consignee</p>
                  <p className="mt-1.5 font-bold">{recipientName}</p>
                  {order.shipAddr1 ? <p>{order.shipAddr1}</p> : null}
                  <p>{recipientCityLine}, {destCountry}</p>
                  {order.phone ? <p>PH: {order.phone}</p> : null}
                  <p className="mt-1 text-[10px] text-slate-500">Customer {order.custNo}</p>
                </div>
              </div>

              {/* Importer of record + customs broker — resolved from the
                  client's Importer/Broker profile, never hardcoded. */}
              <div className="mt-3 grid grid-cols-2 gap-4 text-xs">
                <div className="border border-slate-300 p-3">
                  <p className="text-[10px] font-bold uppercase tracking-widest text-slate-500">Importer of Record</p>
                  {receiverIsImporter ? (
                    <p className="mt-1.5 text-slate-700">
                      <span className="font-bold">Same as consignee</span> (DAP — receiver imports; carrier collects
                      identity documents at destination)
                    </p>
                  ) : (
                    <>
                      <p className="mt-1.5 font-bold">{importer?.name}</p>
                      {importer?.addressLine1 ? <p>{importer.addressLine1}{importer.addressLine2 ? `, ${importer.addressLine2}` : ''}</p> : null}
                      <p>
                        {[importer?.city, importer?.state, importer?.postalCode].filter(Boolean).join(', ')}
                        {importer?.countryCode ? `, ${importer.countryCode}` : ''}
                      </p>
                      {importer?.phone ? <p>PH: {importer.phone}</p> : null}
                      <p className="mt-1 text-[10px] text-slate-500">
                        {[
                          importer?.taxId ? `${importer.taxIdType || 'Tax ID'}: ${importer.taxId}` : null,
                          importer?.eori ? `EORI: ${importer.eori}` : null,
                          importer?.ioss ? `IOSS: ${importer.ioss}` : null,
                          importer?.iec ? `IEC: ${importer.iec}` : null,
                          importer?.gstin ? `GSTIN: ${importer.gstin}` : null,
                          importer?.companyReg ? `Reg: ${importer.companyReg}` : null,
                        ]
                          .filter(Boolean)
                          .join(' · ') || 'No tax identifiers on file'}
                      </p>
                    </>
                  )}
                </div>
                <div className="border border-slate-300 p-3">
                  <p className="text-[10px] font-bold uppercase tracking-widest text-slate-500">Customs Broker</p>
                  {brokerage === 'BROKER_SELECT' && broker ? (
                    <>
                      <p className="mt-1.5 font-bold">{broker.name}</p>
                      {broker.company ? <p>{broker.company}</p> : null}
                      {broker.addressLine1 ? <p>{broker.addressLine1}{broker.addressLine2 ? `, ${broker.addressLine2}` : ''}</p> : null}
                      <p>
                        {[broker.city, broker.state, broker.postalCode].filter(Boolean).join(', ')}
                        {broker.countryCode ? `, ${broker.countryCode}` : ''}
                      </p>
                      <p className="mt-1 text-[10px] text-slate-500">
                        {[
                          broker.brokerId ? `Broker ID: ${broker.brokerId}` : null,
                          broker.license ? `License: ${broker.license}` : null,
                          broker.phone ? `PH: ${broker.phone}` : null,
                        ]
                          .filter(Boolean)
                          .join(' · ') || 'Broker Select Option'}
                      </p>
                    </>
                  ) : (
                    <p className="mt-1.5 text-slate-700">
                      <span className="font-bold">{carrierDisplay} brokerage</span> (included with international
                      service — no third-party broker designated)
                    </p>
                  )}
                </div>
              </div>

              <div className="mt-3 grid grid-cols-4 gap-2 text-center text-[11px]">
                {[
                  ['Carrier', carrierDisplay],
                  ['Carrier Account', accountNumber ? `${accountNumber}`.slice(0, 14) : '—'],
                  ['Terms of Sale', customsDefaults?.incoterms || (receiverIsImporter ? 'DAP' : 'DDP')],
                  ['Currency', customsDefaults?.currency || 'USD'],
                ].map(([k, v]) => (
                  <div key={k} className="border border-slate-300 p-2">
                    <p className="text-[9px] font-bold uppercase tracking-widest text-slate-500">{k}</p>
                    <p className="mt-0.5 truncate font-semibold">{v}</p>
                  </div>
                ))}
              </div>

              <table className="mt-4 w-full border-collapse text-xs">
                <thead>
                  <tr className="bg-slate-900 text-left text-white">
                    <th className="border border-slate-900 px-2 py-1.5">#</th>
                    <th className="border border-slate-900 px-2 py-1.5">Item</th>
                    <th className="border border-slate-900 px-2 py-1.5">Description</th>
                    <th className="border border-slate-900 px-2 py-1.5">HS Code</th>
                    <th className="border border-slate-900 px-2 py-1.5">Ctry Mfg</th>
                    <th className="border border-slate-900 px-2 py-1.5 text-right">Unit Qty</th>
                    <th className="border border-slate-900 px-2 py-1.5 text-right">Unit Value</th>
                    <th className="border border-slate-900 px-2 py-1.5 text-right">Commodity Value</th>
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line) => (
                    <tr key={line.id}>
                      <td className="border border-slate-300 px-2 py-1.5">{line.lineNo}</td>
                      <td className="border border-slate-300 px-2 py-1.5">{line.itemNo || '—'}</td>
                      <td className="border border-slate-300 px-2 py-1.5">
                        {line.itemDescription || line.description || order.goodsDesc || '—'}
                      </td>
                      <td className="border border-slate-300 px-2 py-1.5">{line.hsCode || '—'}</td>
                      <td className="border border-slate-300 px-2 py-1.5">{line.countryOfOrigin || '—'}</td>
                      <td className="border border-slate-300 px-2 py-1.5 text-right">{line.qtyShipped ?? '—'} EA</td>
                      <td className="border border-slate-300 px-2 py-1.5 text-right">${money(line.unitPrice)}</td>
                      <td className="border border-slate-300 px-2 py-1.5 text-right font-semibold">${money(line.totalPrice)}</td>
                    </tr>
                  ))}
                  {!lines.length ? (
                    <tr>
                      <td colSpan={8} className="border border-slate-300 px-2 py-6 text-center text-slate-500">
                        No line items recorded — declared as {order.goodsDesc || 'general merchandise'}.
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>

              <div className="mt-3 flex justify-end">
                <div className="w-72 text-xs">
                  <div className="flex justify-between border-b border-slate-300 py-1">
                    <span>Total shipment weight</span>
                    <span className="font-semibold">{order.weight ?? '—'} kg</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-300 py-1">
                    <span>Total quantity</span>
                    <span className="font-semibold">{totalQty} EA</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-300 py-1">
                    <span>Declared customs value</span>
                    <span className="font-semibold">${money(customsTotal)}</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-300 py-1">
                    <span>Total commodity value</span>
                    <span className="font-semibold">${money(invoiceTotal)}</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-300 py-1">
                    <span>Freight amount</span>
                    <span className="font-semibold">$0.00</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-300 py-1">
                    <span>Insurance / other</span>
                    <span className="font-semibold">$0.00</span>
                  </div>
                  <div className="mt-1 flex justify-between bg-slate-900 px-2 py-1.5 text-white">
                    <span className="font-bold">TOTAL INVOICE</span>
                    <span className="font-bold">${money(invoiceTotal)} USD</span>
                  </div>
                </div>
              </div>

              <div className="mt-6 border-t border-slate-300 pt-4 text-[11px] leading-5 text-slate-600">
                <p>
                  These items are authorized for export only to the country of ultimate destination for use by the
                  ultimate consignee herein identified. They may not be resold, transferred, or otherwise disposed of
                  to any other country or person other than the authorized ultimate consignee.
                </p>
                <p className="mt-2 font-semibold text-slate-800">
                  I declare all information contained in this invoice to be true and correct.
                </p>
                <div className="mt-8 grid grid-cols-3 gap-6">
                  <div>
                    <p className="pb-1 font-semibold text-slate-800">{shipper?.name || ''}</p>
                    <div className="border-t border-slate-900 pt-1">Signature of broker / company</div>
                  </div>
                  <div>
                    <p className="pb-1 font-semibold text-slate-800">{formatDate(shipDate)}</p>
                    <div className="border-t border-slate-900 pt-1">Date</div>
                  </div>
                  <div className="self-end text-right text-[10px] text-slate-400">Page 1 of 1</div>
                </div>
              </div>
            </div>
          )}
        </div>
      ) : null}

      {trackingOpen && order?.orderNo != null ? (
        <TrackingTimelineModal
          orderNo={order.orderNo}
          onClose={() => setTrackingOpen(false)}
        />
      ) : null}
    </div>
  )
}
