import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
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

/** Commercial-invoice date style: 05/18/2026 (US numeric, as FedEx prints). */
const ciDate = (value?: string | null) => {
  const parsed = value ? new Date(value) : new Date()
  const d = Number.isNaN(parsed.getTime()) ? new Date() : parsed
  return `${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}/${d.getFullYear()}`
}

/** Reason-for-export code → the human "Purpose" a customs invoice prints. */
const PURPOSE_LABELS: Record<string, string> = {
  SOLD: 'Commercial / Sold',
  COMMERCIAL: 'Commercial / Sold',
  SALE: 'Commercial / Sold',
  NOT_SOLD: 'Commercial / Not Sold',
  GIFT: 'Gift / Personal',
  PERSONAL: 'Gift / Personal',
  PERSONAL_EFFECTS: 'Personal Effects',
  SAMPLE: 'Commercial Sample',
  REPAIR: 'Repair / Return',
  RETURN: 'Repair / Return',
  REPAIR_AND_RETURN: 'Repair / Return',
  INTERCOMPANY: 'Intercompany Data',
}
const purposeLabel = (reason?: string | null) => {
  const key = (reason || '').trim().toUpperCase().replace(/[\s-]+/g, '_')
  return PURPOSE_LABELS[key] || (reason ? reason.trim() : 'Commercial / Sold')
}

/** A titled label:value line as the FedEx commercial invoice lays them out. */
const KV = ({ label, value }: { label: string; value?: ReactNode }) => (
  <div className="flex gap-1">
    <span className="shrink-0 font-semibold text-slate-500">{label} :</span>
    <span className="min-w-0 break-words text-slate-900">{value ?? ''}</span>
  </div>
)

/**
 * PR #535 — E.164-style label phone formatting. Carriers prefix the
 * country dial code (`1` for US, `44` for GB, `91` for IN, …) when
 * emitting the printed label. Preview mirrors that behavior so
 * on-screen matches the printed form. Country code table covers the
 * common ones; unknown countries fall through to raw digits. Blank
 * dial codes (VA, etc.) stay bare.
 */
const DIAL_CODE_BY_COUNTRY: Record<string, string> = {
  US: '1', CA: '1', GB: '44', IN: '91', AU: '61', DE: '49', FR: '33',
  IT: '39', ES: '34', NL: '31', BE: '32', CH: '41', SE: '46', NO: '47',
  DK: '45', FI: '358', JP: '81', CN: '86', KR: '82', SG: '65', HK: '852',
  MX: '52', BR: '55', AR: '54', CL: '56', CO: '57', PE: '51',
  AE: '971', ZA: '27', NZ: '64', IE: '353', PT: '351', AT: '43',
}
const formatPhoneForLabel = (phone: string | null | undefined, country: string | null | undefined): string => {
  if (!phone) return ''
  const digits = phone.replace(/[^0-9]/g, '')
  if (!digits) return phone
  const cc = DIAL_CODE_BY_COUNTRY[(country || '').toUpperCase()]
  if (!cc) return digits
  return digits.startsWith(cc) ? digits : cc + digits
}

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
  // PR #538 — carrier-ZPL PNG preview state. Three-way:
  //   'unknown' — haven't tried; renders the JSX facsimile
  //   'ready'   — backend served the PNG; render <img> in place of the facsimile
  //   'unavailable' — backend returned 404/502 (flag off, or ZPL passthrough failed);
  //                   silently fall back to the JSX facsimile
  // Probing done via a HEAD request on mount + on pkgIndex change; no
  // extra render burden when the endpoint 404s. Kept inline (no
  // separate hook) so a stale bundle without the endpoint just picks
  // the 'unavailable' branch cleanly.
  const [carrierPreviewState, setCarrierPreviewState] =
    useState<'unknown' | 'ready' | 'unavailable'>('unknown')
  const [zplBusy, setZplBusy] = useState(false)
  const [pdfBusy, setPdfBusy] = useState(false)
  // Sprint 52 PR A — same re-entrancy guard as the ZPL flow. Ref
  // checked synchronously so a fast double-click can't fire two fetches.
  const pdfInFlightRef = useRef(false)
  /** Audit R2 #390 — re-entrancy guard. The disabled={zplBusy} attribute
   *  on the two buttons stops a click after React commits the state update,
   *  but a fast double-click can fire twice before the first setZplBusy(true)
   *  renders. Ref checked synchronously inside fetchZpl short-circuits the
   *  second call so we never send two network requests for the same ZPL. */
  const zplInFlightRef = useRef(false)

  const fetchZpl = async (): Promise<string | null> => {
    if (zplInFlightRef.current) return null
    zplInFlightRef.current = true
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
      zplInFlightRef.current = false
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

  const downloadPdf = async () => {
    if (pdfInFlightRef.current) return
    pdfInFlightRef.current = true
    setPdfBusy(true)
    try {
      const blob = await orderService.getLabelPdf(orderNo, pkgIndex)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = pkgCount > 1
        ? `label-${orderNo}-pkg${pkgIndex}of${pkgCount}.pdf`
        : `label-${orderNo}.pdf`
      link.click()
      URL.revokeObjectURL(url)
      notify.success(`${link.download} downloaded.`)
    } catch (err) {
      notify.apiError(err, 'Failed to fetch the label PDF.')
    } finally {
      pdfInFlightRef.current = false
      setPdfBusy(false)
    }
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

  // PR #544 — HEAD-probe useEffect moved BELOW the pkgIndex
  // declaration (originally lived here). eslint no-use-before-define
  // won't allow the effect to reference pkgIndex when the const is
  // declared later in the same scope; keeping the effect adjacent to
  // pkgIndex reads more clearly anyway.

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
  // Terms of sale: the ORDER's own incoterm (what the operator picked on this
  // shipment) must beat the customs profile's tenant-wide default — the old
  // priority let a stale profile DAP override an order created as DDP, which
  // misstates duty liability on a customs document. Fallback derives from the
  // importer profile type only when neither carries a value.
  const profileSaysReceiver = !importer || importer.type === 'RECEIVER'
  const termsOfSale = payload?.customs?.incoterms || customsDefaults?.incoterms
    || (profileSaysReceiver ? 'DAP' : 'DDP')
  // Importer of record follows the INCOTERM, not just the profile: under DDP
  // the seller/shipper clears customs and pays duties, so "receiver is the
  // importer" would be wrong even when no importer profile is configured.
  const receiverIsImporter = termsOfSale === 'DDP' ? false : profileSaysReceiver

  // TENANT users may only open their own orders.
  const ownTenant = getTenantIdForUser(normalizeRole(role), username)
  const tenantBlocked = Boolean(ownTenant && order && (order.tenantId || order.custNo)?.toUpperCase() !== ownTenant)

  // Account details from the cascade resolution (falls back to legacy payloads).
  const accountCarrierCode = resolution?.carrierCode || legacyAccount?.carrierCode || order?.shipviaCd || null
  const accountNumber = resolution?.accountNumber || legacyAccount?.accountNumber || null
  const environment = (resolution?.environment || legacyAccount?.environment || 'SANDBOX').toUpperCase()
  const isSandbox = environment !== 'PRODUCTION'

  const carrierId = normalizeCarrierCode(accountCarrierCode)
  const theme = (carrierId && CARRIER_THEMES[carrierId]) || fallbackTheme
  const carrierDisplay = formatCarrierName(accountCarrierCode)
  const shipDate = label?.generatedAt || order?.createdDate

  // Commercial-invoice lines: prefer the per-order customs items entered against
  // the order (manual/international shipments); fall back to the ERP order lines.
  const customsItems = payload?.customs?.items ?? []
  // Total shipped units — used to distribute the parcel weight across lines
  // when the commodities carry no explicit per-item weight of their own.
  const totalItemQty = customsItems.reduce((s, it) => s + (it.quantity ?? 1), 0)
  const lines =
    customsItems.length > 0
      ? customsItems.map((it, i) => {
          const qty = it.quantity ?? 1
          const unit = it.unitValue ?? 0
          // Per-line net weight. Prefer an explicit per-item weight (× qty);
          // otherwise apportion the parcel's total weight by this line's share
          // of the shipped units, so NET/GROSS populate and still sum to the
          // parcel weight. Pack weight is 0 and gross = net (single weight per
          // commodity), matching the FedEx CI commodity layout.
          const netWeight =
            it.weight != null
              ? it.weight * qty
              : order?.weight != null && totalItemQty > 0
                ? (order.weight * qty) / totalItemQty
                : null
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
            netWeight,
          }
        })
      : order?.orderLines || []
  const customsTotal = lines.reduce((sum, line) => sum + (line.customsDeclValue ?? 0), 0)

  // Some order feeds put a bare sequence digit in ship_name; prefer a plausible name.
  // Never falls back to custNo (client code) — that would render the tenant
  // identifier as the parcel's addressee. Placeholder is a literal '-' so
  // mis-populated shipments are visibly broken.
  const rawShipName = order?.shipName?.trim()
  const rawShipAttn = order?.shipAttn?.trim()
  const recipientName =
    (rawShipName && rawShipName.length > 2 ? rawShipName : rawShipAttn) || '-'
  // PR #535 — recipient company as its own line. Previously ship_attn
  // was only rendered when it was used AS the name fallback (short
  // shipName), so a company entered alongside a full recipient name
  // was invisible on-screen — but carriers print it as a distinct
  // COMPANY line on the actual label. Skip when it duplicates the
  // rendered name (avoids double-printing).
  const recipientCompany = rawShipAttn && rawShipAttn !== recipientName ? rawShipAttn : null
  // PR #544 rebase — `recipientCityLine` declaration removed. The only
  // JSX usage was in the packing-slip Consignee block which a parallel
  // dev PR (dev's `40c515f` orders-hardening) refactored out. Removed
  // the dangling declaration to unblock eslint no-unused-vars.
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
  // The commercial invoice is international-only. `activeTab` collapses to the
  // label for domestic shipments so a domestic parcel never renders the empty
  // customs document even if `tab` state is 'invoice' (the tab button that sets
  // it is itself hidden when domestic).
  const activeTab: DocumentTab = isInternational ? tab : 'label'
  const originCountry = (shipper?.countryCode || '').toUpperCase()
  const isUsExport = isInternational && originCountry === 'US'
  // Multi-package: total M comes from the order's package_count column;
  // current N comes from the ?pkg= query param (default 1). Clamped.
  //
  // Audit R2 #388 — clamp against BOTH packageCount AND packages.length
  // when the array is present. Pre-fix, packageCount could disagree with
  // the actual persisted packages (e.g., order.packageCount=5 but only 3
  // label_package rows), so pkgIndex=5 → perPkg falls back to shipment
  // level silently. Now we cap to whichever is smaller so the picker
  // never lands on a "phantom" package.
  const packagesArrayLen = Array.isArray(order?.packages) ? order.packages.length : 0
  const pkgCount = Math.max(1,
    packagesArrayLen > 0 ? packagesArrayLen : (Number(order?.packageCount) || 1))
  const rawPkg = Number(searchParams.get('pkg')) || 1
  const pkgIndex = Math.min(Math.max(1, rawPkg), pkgCount)

  // PR #538/544 — HEAD probe for /label/preview.png. When the backend
  // feature flag label.render-carrier-zpl is on AND the carrier stored
  // parseable ZPL bytes for this order + package, the endpoint returns
  // 200 and we swap the JSX facsimile for an <img>. 404 (flag off /
  // not ZPL) or 502 (renderer failed) → keep the facsimile. Silent
  // probe so a stale bundle against a backend without the endpoint
  // still renders cleanly.
  // Deps include pkgIndex so the package-picker's package change
  // re-fires the probe for that specific package's ZPL.
  useEffect(() => {
    if (!Number.isFinite(orderNo)) return
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset probe state on order/pkg change; HEAD result below transitions to ready/unavailable
    setCarrierPreviewState('unknown')
    orderService.headLabelPreviewPng(orderNo, pkgIndex)
      .then((available) => {
        if (!cancelled) setCarrierPreviewState(available ? 'ready' : 'unavailable')
      })
      .catch(() => {
        if (!cancelled) setCarrierPreviewState('unavailable')
      })
    return () => { cancelled = true }
  }, [orderNo, pkgIndex])

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

  // ---- commercial-invoice header + totals ----------------------------------
  const ciWeightUnit = (payload?.customs?.weightUnit || order?.weightUnit || 'LB').toLowerCase()
  // Order-specific values beat profile defaults (same inversion as incoterms).
  const ciCurrency = (payload?.charges?.currency || payload?.customs?.currency || customsDefaults?.currency || 'USD').toUpperCase()
  const purpose = purposeLabel(payload?.customs?.reasonForExport || customsDefaults?.reasonForExport)
  const freightAmount = payload?.charges?.freight ?? 0
  const insuranceAmount = 0
  const otherAmount = 0
  const totalInvoice = customsTotal + freightAmount + insuranceAmount + otherAmount
  // Gross shipment weight sums each line's net (single weight per line);
  // falls back to the order's own weight when no per-item weights were entered.
  const lineWeightSum = lines.reduce((sum, line) => sum + ((line as { netWeight?: number | null }).netWeight ?? 0), 0)
  const totalShipmentWeight = lineWeightSum > 0 ? lineWeightSum : (order?.weight ?? 0)
  const invoiceRef = `${accountNumber ? `${accountNumber}` : 'AC'}-${trackingNumber || orderDisplay}`

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
      <style>{activeTab === 'label'
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
            {/* Commercial invoice is international-only — a domestic parcel
                crosses no customs border, so there's nothing to declare. */}
            {isInternational ? (
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
            ) : null}
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

          {activeTab === 'label' ? (
            <>
              <button
                type="button"
                onClick={() => {
                  void downloadPdf()
                }}
                disabled={loading || Boolean(error) || tenantBlocked || pdfBusy}
                title="4x6 PDF facsimile of the shipping label (Sprint 52 PR A)"
                data-testid="download-pdf-btn"
                className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiDownload className="h-3.5 w-3.5" />
                {pdfBusy ? 'Fetching…' : 'Download PDF'}
              </button>
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
            Print {activeTab === 'label' ? 'Label' : 'Invoice'}
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
        <>
          {/* PR #548 — Master + child tracking table. Shown for
              multi-package shipments (pkgCount > 1) OR when a
              shipmentBatches[0].masterTrackingNumber differs from
              the shipment-level trackingNumber (rare but possible on
              over-cap splits where the "shipment tracking" happened
              to be piece 1's tracking, not the master).
              print:hidden — operator-facing, not part of the printed
              label; each label itself already has its own tracking. */}
          {activeTab === 'label' &&
              (pkgCount > 1 ||
                (order.shipmentBatches?.length ?? 0) > 1) ? (
            <div className="mb-4 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-[13px] shadow-sm print:hidden">
              <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                Tracking numbers
              </div>
              {(order.shipmentBatches ?? []).map((b) => (
                <div
                  key={`batch-${b.batchSeq ?? 0}`}
                  className="mb-2 border-l-2 border-emerald-500 pl-3"
                >
                  <div className="text-slate-800">
                    <span className="font-semibold">
                      Master{(order.shipmentBatches?.length ?? 0) > 1
                        ? ` (batch ${b.batchSeq})`
                        : ''}
                      :{' '}
                    </span>
                    {b.masterTrackingUrl ? (
                      <a
                        href={b.masterTrackingUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="font-mono text-emerald-700 underline"
                      >
                        {b.masterTrackingNumber ?? '—'}
                      </a>
                    ) : (
                      <span className="font-mono text-slate-800">
                        {b.masterTrackingNumber ?? '—'}
                      </span>
                    )}
                    <span className="ml-2 text-[11px] text-slate-500">
                      {b.carrierCode ?? ''}
                      {b.packageCountInBatch != null
                        ? ` · ${b.packageCountInBatch} pkg${b.packageCountInBatch > 1 ? 's' : ''}`
                        : ''}
                    </span>
                  </div>
                </div>
              ))}
              <table className="w-full border-collapse text-[12px]">
                <thead className="text-left text-slate-500">
                  <tr>
                    <th className="border-b border-slate-200 py-1 pr-3 font-semibold">
                      Pkg
                    </th>
                    <th className="border-b border-slate-200 py-1 pr-3 font-semibold">
                      Child tracking
                    </th>
                    <th className="border-b border-slate-200 py-1 pr-3 font-semibold">
                      Weight
                    </th>
                    <th className="border-b border-slate-200 py-1 font-semibold">
                      Dimensions
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {(order.packages ?? []).map((p) => {
                    const dims =
                      p.length && p.width && p.height
                        ? `${p.length}×${p.width}×${p.height} ${p.dimUnit ?? ''}`.trim()
                        : ''
                    const wt =
                      p.weight != null
                        ? `${p.weight} ${p.weightUnit ?? ''}`.trim()
                        : ''
                    return (
                      <tr
                        key={`pkg-${p.sequenceNumber ?? 0}`}
                        className={
                          p.sequenceNumber === pkgIndex
                            ? 'bg-emerald-50'
                            : undefined
                        }
                      >
                        <td className="py-1 pr-3 font-semibold text-slate-700">
                          {p.sequenceNumber ?? '—'}
                        </td>
                        <td className="py-1 pr-3 font-mono text-slate-800">
                          {p.trackingUrl ? (
                            <a
                              href={p.trackingUrl}
                              target="_blank"
                              rel="noreferrer"
                              className="underline"
                            >
                              {p.trackingNumber ?? '—'}
                            </a>
                          ) : (
                            (p.trackingNumber ?? '—')
                          )}
                        </td>
                        <td className="py-1 pr-3 text-slate-600">{wt || '—'}</td>
                        <td className="py-1 text-slate-600">{dims || '—'}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          ) : null}

        <div className="flex justify-center rounded-[26px] border border-slate-200/80 bg-slate-100/70 p-6 shadow-inner print:border-0 print:bg-white print:p-0 print:shadow-none">
          {activeTab === 'label' && carrierPreviewState === 'ready' ? (
            /* ==================== PR #538 — CARRIER-CANONICAL PNG (from backend zebrash render) ==================== */
            /* When the backend feature flag label.render-carrier-zpl is on
               AND the carrier stored parseable ZPL, backend renders the
               canonical thermal label to PNG server-side. This <img> is
               byte-for-byte what will print — no facsimile approximation.
               onError falls back to the facsimile (rare — HEAD probe
               already checked, but a mid-session backend restart could
               invalidate). */
            <div className="print-doc relative w-[430px] shrink-0 border border-slate-300 bg-white shadow-xl print:w-[3.76in] print:border-0 print:shadow-none">
              <img
                src={orderService.labelPreviewPngUrl(orderNo, pkgIndex)}
                alt={`Shipping label for order ${orderNo}`}
                className="block h-auto w-full"
                onError={() => setCarrierPreviewState('unavailable')}
                data-testid="label-preview-png"
              />
            </div>
          ) : activeTab === 'label' ? (
            /* ==================== 4x6 SHIPPING LABEL (JSX facsimile fallback) ==================== */
            /* Rendered when the carrier PNG isn't available (flag off,
               non-ZPL artifact, or backend renderer error). Approximates
               the carrier layout from DB fields; see
               project_label_preview_audit.md for the known-divergence
               taxonomy this eliminates when carrierPreviewState=ready. */
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
                    INCOTERMS: {termsOfSale}
                  </p>
                ) : null}
                <div className="mt-1 grid grid-cols-[1.08fr_1fr] font-mono text-[10px] leading-[13px]">
                  <div className="pr-2 uppercase">
                    <p>ORIGIN ID:{(shipper?.state || 'XX').toUpperCase()}{(shipper?.postalCode || '').slice(0, 2)}A&nbsp;&nbsp;{(shipper?.phone || '').replace(/\s+/g, '')}</p>
                    <p>{shipper?.name}</p>
                    {/* PR #535 — shipper COMPANY line. Backend
                        addressMap emits `company` when the address's
                        own name (warehouse alias) differs from the
                        client's registered name; otherwise null and
                        this line is suppressed. */}
                    {shipper?.company ? <p>{shipper.company}</p> : null}
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
                      })()} {(() => {
                        // PR #535 — weight-unit fallback now shipper-country
                        // aware. Prior 'KG' hardcode showed the wrong unit on
                        // every US shipment where label_package rows didn't
                        // persist (legacy orders + some intl paths). US
                        // shipper defaults to LB (matches backend
                        // ShipmentDefaultsResolver.DEFAULT_WEIGHT_UNIT); every
                        // other origin defaults to KG.
                        const unit = perPkgWeightUnit || (payload?.order as { weightUnit?: string | null } | null | undefined)?.weightUnit
                        if (unit) return unit.toUpperCase()
                        return (shipper?.countryCode || '').toUpperCase() === 'US' ? 'LB' : 'KG'
                      })()}
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
                    {/* PR #535 — recipient COMPANY (ship_attn) as its own
                        line between name and street. Carriers print this
                        distinct line whenever the caller sent both a
                        recipient.name and recipient.company. */}
                    {recipientCompany ? (
                      <p className="text-[18px] font-black uppercase leading-[22px]">{recipientCompany}</p>
                    ) : null}
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
                {/* PR #535 — recipient phone with country code. FedEx /
                    UPS / DHL wire their carrier-side E.164 rewrite
                    (prepend country dial code) so the printed label
                    shows the prefixed form; preview should mirror.
                    Uses shiptoCountryCd to derive the prefix (+1 for
                    US, blank when unknown). */}
                {order.phone ? (
                  <p className="mt-0.5 text-[11px] font-bold leading-[13px]">{formatPhoneForLabel(order.phone, destCountry)}</p>
                ) : null}
                <div className="grid grid-cols-2 pr-8 text-[9px] font-bold leading-[12px]">
                  <span>INV:</span>
                  <span>REF: {orderDisplay}</span>
                  {/* PR #543 — PO / DEPT rows re-added. PO derives from the
                      order source: manual/bulk → "MAN{orderNo}"; WMS →
                      wmsExternalId when present else orderNo; other sources
                      → orderNo bare. Matches the backend wire logic in
                      CarrierServiceImpl.computeOrderPoNumber so the JSX
                      facsimile fallback (used when carrier ZPL isn't
                      available) prints the same value the real carrier
                      label would. */}
                  <span>PO: {(() => {
                    const src = (order.source || '').toUpperCase()
                    const orderNoStr = order.orderNo != null ? String(order.orderNo) : ''
                    if (!orderNoStr) return ''
                    if (src === 'MANUAL' || src === 'BULK') return 'MAN' + orderNoStr
                    if (src === 'WMS') return (order as { wmsExternalId?: string | null }).wmsExternalId || orderNoStr
                    return orderNoStr
                  })()}</span>
                  <span>DEPT: {order.custNo || ''}</span>
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
                  {(perPkgWeight ?? order.weight) ?? '-'} {(perPkgWeightUnit || order.weightUnit || ((shipper?.countryCode || '').toUpperCase() === 'US' ? 'LB' : 'KG')).toUpperCase()}
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
            <div className="print-doc w-full max-w-[820px] bg-white p-8 text-[11px] leading-5 text-slate-900 shadow-xl print:max-w-none print:p-0 print:shadow-none">
              {/* Header — title + shipment meta, as the FedEx CI lays it out. */}
              <div className="flex items-start justify-between gap-6 border-b-2 border-slate-900 pb-3">
                <div>
                  <h1 className="text-2xl font-black tracking-tight">Commercial Invoice</h1>
                  <p className="mt-0.5 text-[11px] text-slate-500">International shipping document — customs declaration</p>
                  <p className="mt-1 font-mono text-[11px] font-semibold text-slate-600">{invoiceRef}</p>
                </div>
                <div className="w-64 shrink-0 space-y-0.5">
                  <KV label="Ship Date" value={ciDate(shipDate)} />
                  <KV label="International Tracking#" value={trackingNumber || 'Pending'} />
                  <KV label="Purpose" value={purpose} />
                  <KV label="Nbr pkgs" value={pkgCount} />
                  <KV label="Invoice #" value={`INV-${orderDisplay}`} />
                </div>
              </div>

              {/* Parties: Shipper + Consignee, then Broker + Importer. */}
              <div className="mt-3 grid grid-cols-2 gap-3">
                <div className="border border-slate-300 p-3">
                  <p className="mb-1.5 text-[10px] font-bold uppercase tracking-widest text-slate-500">Shipper</p>
                  <p className="font-bold">{shipper?.name || '—'}</p>
                  {shipper?.addressLine1 ? <p>{shipper.addressLine1}</p> : null}
                  {shipper?.addressLine2 ? <p>{shipper.addressLine2}</p> : null}
                  <p>{[shipper?.city, shipper?.postalCode, shipper?.state, shipper?.countryCode].filter(Boolean).join(', ')}</p>
                  <div className="mt-1"><KV label="PH" value={shipper?.phone} /></div>
                </div>
                <div className="border border-slate-300 p-3">
                  <p className="mb-1.5 text-[10px] font-bold uppercase tracking-widest text-slate-500">Consignee</p>
                  <p className="font-bold">{order.shipAttn || recipientName}</p>
                  {order.shipAttn && order.shipAttn !== recipientName ? <p>{recipientName}</p> : null}
                  {order.shipAddr1 ? <p>{order.shipAddr1}</p> : null}
                  <p>{[order.shiptoCity, order.shiptoZip, order.shiptoState, destCountry].filter(Boolean).join(', ')}</p>
                  <div className="mt-1 space-y-0.5">
                    <KV label="PH" value={order.phone} />
                    <KV label="GSTIN" value={importer?.gstin} />
                    <KV label="IRS/EIN" value={importer?.taxIdType === 'EIN' ? importer?.taxId : undefined} />
                    <KV label="Food Shipment" value="N" />
                    <KV label="PN/KN" value="" />
                  </div>
                </div>
                <div className="border border-slate-300 p-3">
                  <p className="mb-1.5 text-[10px] font-bold uppercase tracking-widest text-slate-500">Broker</p>
                  {brokerage === 'BROKER_SELECT' && broker ? (
                    <>
                      <p className="font-bold">{broker.name || broker.company || '—'}</p>
                      {broker.company && broker.name ? <p>{broker.company}</p> : null}
                      {broker.addressLine1 ? <p>{broker.addressLine1}{broker.addressLine2 ? `, ${broker.addressLine2}` : ''}</p> : null}
                      <p>{[broker.city, broker.postalCode, broker.state, broker.countryCode].filter(Boolean).join(', ')}</p>
                      <div className="mt-1 space-y-0.5">
                        <KV label="PH" value={broker.phone} />
                        {broker.brokerId ? <KV label="Broker ID" value={broker.brokerId} /> : null}
                        {broker.license ? <KV label="License" value={broker.license} /> : null}
                      </div>
                    </>
                  ) : (
                    <p className="text-slate-700">
                      <span className="font-bold">{carrierDisplay} brokerage</span> — included with the international
                      service; no third-party broker designated.
                    </p>
                  )}
                </div>
                <div className="border border-slate-300 p-3">
                  <p className="mb-1.5 text-[10px] font-bold uppercase tracking-widest text-slate-500">Importer</p>
                  {receiverIsImporter ? (
                    <p className="text-slate-700">
                      <span className="font-bold">Same as consignee</span> — {termsOfSale}: the receiver is the
                      importer of record; the carrier collects identity documents at destination.
                    </p>
                  ) : !importer ? (
                    /* DDP with no importer profile: duty liability sits with the
                       seller — never claim the receiver imports under DDP. */
                    <p className="text-slate-700">
                      <span className="font-bold">Shipper / seller</span> — {termsOfSale}: the sender is the
                      importer of record and pays duties &amp; taxes at destination.
                    </p>
                  ) : (
                    <>
                      <p className="font-bold">{importer?.name || '—'}</p>
                      {importer?.contact ? <p>{importer.contact}</p> : null}
                      {importer?.addressLine1 ? <p>{importer.addressLine1}{importer.addressLine2 ? `, ${importer.addressLine2}` : ''}</p> : null}
                      <p>{[importer?.city, importer?.postalCode, importer?.state, importer?.countryCode].filter(Boolean).join(', ')}</p>
                      <div className="mt-1 space-y-0.5">
                        <KV label="PH" value={importer?.phone} />
                        {importer?.taxId ? <KV label={importer?.taxIdType || 'Tax ID'} value={importer.taxId} /> : null}
                        {importer?.eori ? <KV label="EORI" value={importer.eori} /> : null}
                        {importer?.ioss ? <KV label="IOSS" value={importer.ioss} /> : null}
                        {importer?.iec ? <KV label="IEC" value={importer.iec} /> : null}
                        {importer?.gstin ? <KV label="GSTIN" value={importer.gstin} /> : null}
                      </div>
                    </>
                  )}
                </div>
              </div>

              {/* Commodities — one detailed block per line, FedEx CI layout. */}
              <div className="mt-3 border border-slate-300">
                <div className="bg-slate-900 px-3 py-1.5 text-[10px] font-bold uppercase tracking-widest text-white">
                  Commodities
                </div>
                {lines.length ? (
                  lines.map((line, i) => {
                    const net = (line as { netWeight?: number | null }).netWeight
                    return (
                      <div key={line.id} className={`px-3 py-2 ${i > 0 ? 'border-t border-slate-200' : ''}`}>
                        <div className="grid grid-cols-3 gap-x-4 gap-y-0.5">
                          <KV label="MARK/NBRS" value={line.itemNo} />
                          <KV label="HS CODE" value={line.hsCode} />
                          <KV label="CTRY MFG" value={line.countryOfOrigin} />
                          <KV label="NET WT" value={net != null ? `${money(net)} ${ciWeightUnit}` : ''} />
                          <KV label="PACK WT" value={`${money(0)} ${ciWeightUnit}`} />
                          <KV label="GROSS WT" value={net != null ? `${money(net)} ${ciWeightUnit}` : ''} />
                          <KV label="UNIT QTY" value={`${line.qtyShipped ?? 0} EA`} />
                          <KV label="UNIT VALUE" value={`$${money(line.unitPrice)}`} />
                          <KV label="COMMODITY VALUE" value={`$${money(line.totalPrice)} ${ciCurrency}`} />
                          <KV label="LICENSE" value="" />
                          <KV label="EX DATE" value="" />
                        </div>
                        <div className="mt-0.5"><KV label="DESCRIPTION" value={line.itemDescription || line.description || order.goodsDesc} /></div>
                      </div>
                    )
                  })
                ) : (
                  <div className="px-3 py-6 text-center text-slate-500">
                    No line items recorded — declared as {order.goodsDesc || 'general merchandise'}.
                  </div>
                )}
              </div>

              {/* Totals. */}
              <div className="mt-3 flex justify-end">
                <div className="w-80 space-y-0.5">
                  <div className="flex justify-between border-b border-slate-200 py-0.5">
                    <span className="font-semibold text-slate-500">TOTAL SHIPMENT WEIGHT :</span>
                    <span className="font-semibold tabular-nums">{money(totalShipmentWeight)} {ciWeightUnit}</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-200 py-0.5">
                    <span className="font-semibold text-slate-500">TOTAL COMMODITY VALUE :</span>
                    <span className="font-semibold tabular-nums">${money(customsTotal)}</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-200 py-0.5">
                    <span className="font-semibold text-slate-500">FREIGHT AMOUNT :</span>
                    <span className="font-semibold tabular-nums">${money(freightAmount)}</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-200 py-0.5">
                    <span className="font-semibold text-slate-500">INSURANCE AMOUNT :</span>
                    <span className="font-semibold tabular-nums">${money(insuranceAmount)}</span>
                  </div>
                  <div className="flex justify-between border-b border-slate-200 py-0.5">
                    <span className="font-semibold text-slate-500">OTHER AMOUNT :</span>
                    <span className="font-semibold tabular-nums">${money(otherAmount)}</span>
                  </div>
                  <div className="mt-1 flex justify-between bg-slate-900 px-2 py-1.5 text-white">
                    <span className="font-bold">TOTAL INVOICE :</span>
                    <span className="font-bold tabular-nums">${money(totalInvoice)} {ciCurrency}</span>
                  </div>
                  <div className="flex justify-between py-0.5">
                    <span className="font-semibold text-slate-500">TERMS OF SALE :</span>
                    <span className="font-bold">{termsOfSale}</span>
                  </div>
                </div>
              </div>

              {/* Legal + declaration + signature. */}
              <div className="mt-5 border-t border-slate-300 pt-3 text-slate-600">
                <p>
                  {isUsExport
                    ? 'These items are controlled by the U.S. Government and authorized for export only to the country of ultimate destination for use by the ultimate consignee or end-user(s) herein identified. They may not be resold, transferred, or otherwise disposed of, to any other country or to any person other than the authorized ultimate consignee or end-user(s), either in their original form or after being incorporated into other items, without first obtaining approval from the U.S. Government or as otherwise authorized by U.S. law and regulations.'
                    : 'These items are authorized for export only to the country of ultimate destination for use by the ultimate consignee herein identified. They may not be resold, transferred, or otherwise disposed of to any other country or person other than the authorized ultimate consignee.'}
                </p>
                <div className="mt-2"><KV label="COMMENTS" value={payload?.customs?.notes} /></div>
                <p className="mt-2 font-semibold text-slate-800">I declare all information in this invoice to be true and correct.</p>
                <div className="mt-6 flex items-end justify-between gap-6">
                  <div className="min-w-0">
                    <p className="font-semibold text-slate-800">{shipper?.name || ''}</p>
                    <p className="text-slate-600">{ciDate(shipDate)}</p>
                    <div className="mt-1 w-56 border-t border-slate-900 pt-1 text-[10px]">Signature of shipper / company</div>
                  </div>
                  <div className="shrink-0 text-right">
                    <KV label="Date" value={ciDate(shipDate)} />
                    <KV label="Page Number" value="1" />
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
        </>
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
