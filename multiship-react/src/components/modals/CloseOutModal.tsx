import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  FiAlertCircle,
  FiAlertTriangle,
  FiCalendar,
  FiCheckCircle,
  FiDownload,
  FiFileText,
  FiHash,
  FiMapPin,
  FiTruck,
  FiX,
} from 'react-icons/fi'
import { Link } from 'react-router-dom'
import { manifestService, type ManifestEntry, type ManifestRequest, type ManifestResponse } from '../../api/manifestService'
import { notify } from '../../utils/notify'
import { useModalDismiss } from '../../hooks/useModalDismiss'
import { useConnectedCarriers } from '../../hooks/useConnectedCarriers'
import { settingsPaths } from '../../routes/workspaceRoutes'

/**
 * Sprint 34 — end-of-day close-out modal. Operator picks a carrier,
 * confirms the tracking numbers to include (default = every non-empty
 * tracking number the parent supplied), and submits. Backend calls the
 * carrier's manifest endpoint and returns an ID + optional PDF. "Close
 * whole day" skips the list and closes the carrier's whole day (shared
 * with Settings → Pickups & End-of-Day).
 */
export interface CloseOutModalProps {
  onClose: () => void
  /** Pre-populated tracking numbers from the parent (e.g. today's
   *  generated labels for the workspace). */
  trackingNumbers: string[]
  /** Optional pre-filled defaults (ship-from address). */
  defaults?: Partial<ManifestRequest>
}

const CARRIERS = [
  { code: 'UPS', name: 'UPS', method: 'End of Day manifest' },
  { code: 'FEDEX', name: 'FedEx', method: 'Ground CloseShipment' },
  { code: 'USPS', name: 'USPS', method: 'SCAN Form' },
  { code: 'DHL', name: 'DHL', method: 'manifest is implicit via the pickup — no separate close call' },
] as const

const inputCls =
  'w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12.5px] text-[#1f150c] outline-none transition focus:border-[#412d15] focus:ring-1 focus:ring-[#412d15]'

/** Segmented-pill button class (selected = espresso filled). */
const pillCls = (active: boolean) =>
  `inline-flex items-center justify-center gap-1.5 rounded-lg border px-2 py-2 text-[12px] font-semibold transition ${
    active
      ? 'border-[#1f150c] bg-[#1f150c] text-[#f4eede]'
      : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
  }`

export default function CloseOutModal({ onClose, trackingNumbers, defaults }: CloseOutModalProps) {
  // A11y audit — focus trap + Escape-to-close + focus restoration.
  const dialogRef = useRef<HTMLDivElement>(null)
  useModalDismiss(true, dialogRef, onClose)
  const today = new Date().toISOString().slice(0, 10)
  const [form, setForm] = useState<ManifestRequest>({
    carrierCode: defaults?.carrierCode ?? 'UPS',
    customerNo: defaults?.customerNo ?? null,
    trackingNumbers,
    closeDate: defaults?.closeDate ?? today,
    addressName: defaults?.addressName ?? '',
    addressLine1: defaults?.addressLine1 ?? '',
    addressLine2: defaults?.addressLine2 ?? '',
    city: defaults?.city ?? '',
    state: defaults?.state ?? '',
    postalCode: defaults?.postalCode ?? '',
    countryCode: defaults?.countryCode ?? 'US',
  })
  const [trackingText, setTrackingText] = useState(trackingNumbers.join('\n'))
  const [result, setResult] = useState<ManifestResponse | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const update = (patch: Partial<ManifestRequest>) => setForm((f) => ({ ...f, ...patch }))

  // Show only carriers connected on this app (active account); null = unknown
  // (loading / failed) → fall back to all carriers so the picker still works.
  const connected = useConnectedCarriers()
  const carriers = connected && connected.size > 0
    ? CARRIERS.filter((c) => connected.has(c.code)) : [...CARRIERS]
  useEffect(() => {
    if (connected && connected.size > 0 && !connected.has(form.carrierCode) && carriers.length > 0) {
      update({ carrierCode: carriers[0].code })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected])

  const parsedTracking = trackingText
    .split(/\s+/)
    .map((t) => t.trim())
    .filter((t) => t.length > 0)

  const canSubmit = Boolean(form.carrierCode && parsedTracking.length > 0)

  const notifyResult = (d: ManifestResponse | null, wholeDay: boolean) => {
    if (d?.status === 'MANIFESTED') {
      const isSplit = d.manifests && d.manifests.length > 0
      notify.success(isSplit
        ? `Manifested ${d.trackingCount} shipment(s) across ${d.manifests!.length} fleets`
        : `Manifested ${d.trackingCount} shipment(s)${d.manifestId ? ` · ${d.manifestId}` : ''}`)
    } else if (d?.status === 'EMPTY') {
      notify.info(d.message)
    } else if (d?.status === 'PARTIAL') {
      // No 'warning' variant on notify; info keeps the toast non-red while the
      // amber ResultBanner below carries the per-fleet detail.
      notify.info(d.message)
    } else if (d) {
      notify.error(d.message)
    }
    void wholeDay
  }

  const submit = async () => {
    if (!canSubmit) return
    setSubmitting(true)
    setResult(null)
    try {
      const response = await manifestService.closeOut({ ...form, trackingNumbers: parsedTracking })
      setResult(response.data ?? null)
      notifyResult(response.data ?? null, false)
    } catch (e) {
      notify.apiError(e, 'Close-out call failed.')
    } finally {
      setSubmitting(false)
    }
  }

  // Shared with Settings → Pickups & End-of-Day: close the whole day for this
  // carrier (the backend gathers the day's open labels) rather than only the
  // typed/selected trackings. Same POST /manifests/close-day path.
  const submitWholeDay = async () => {
    if (!form.carrierCode) return
    setSubmitting(true)
    setResult(null)
    try {
      const response = await manifestService.closeOutDay({
        carrierCode: form.carrierCode,
        customerNo: form.customerNo,
        closeDate: form.closeDate,
      })
      setResult(response.data ?? null)
      notifyResult(response.data ?? null, true)
    } catch (e) {
      notify.apiError(e, 'Close-out call failed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Close out day"
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[94vh] w-full max-w-[860px] flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-[0_30px_80px_rgba(31,21,12,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* header */}
        <div className="flex items-start justify-between gap-3 border-b border-[#eee6d6] px-5 py-4">
          <div className="flex items-start gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#1f150c] text-[#f4eede] shadow-sm">
              <FiFileText className="h-4 w-4" />
            </span>
            <div>
              <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-[#b6a684]">
                End of day
              </p>
              <h3 className="mt-0.5 text-[15px] font-semibold text-[#1f150c]">Close out the day</h3>
              <p className="mt-1 text-[11.5px] text-[#6b5c42]">
                Manifests the day's tracking numbers so the driver can accept the parcels at pickup.
              </p>
              <p className="mt-1 text-[11px] text-[#a1906d]">
                Day-wide close, automation and history in{' '}
                <Link to={settingsPaths.pickupsEod} onClick={onClose} className="font-semibold text-[#5a4526] underline">
                  Pickups &amp; End-of-Day
                </Link>.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#6b5c42] transition hover:bg-[#faf7f0]"
          >
            <FiX className="h-3.5 w-3.5" />
          </button>
        </div>

        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto bg-[#faf7f0]/50 px-5 py-4">
          <div className="grid gap-3 md:grid-cols-2 md:items-start">
          <Section icon={<FiTruck className="h-3.5 w-3.5" />} title="Carrier & date">
            <span className="mb-1 block text-[10.5px] font-semibold text-[#5a4526]">Carrier</span>
            <div className="flex flex-wrap gap-2">
              {carriers.map((c) => (
                <button key={c.code} type="button"
                        onClick={() => update({ carrierCode: c.code })}
                        className={`${pillCls(form.carrierCode === c.code)} min-w-[76px] flex-1`}>
                  {c.name}
                </button>
              ))}
            </div>
            <p className="mt-1.5 flex items-start gap-1 text-[10.5px] text-[#9a8b70]">
              {form.carrierCode === 'DHL' ? <FiAlertTriangle className="mt-0.5 h-3 w-3 shrink-0" /> : null}
              {CARRIERS.find((c) => c.code === form.carrierCode)?.method}
            </p>
            <div className="mt-3">
              <Field label="Close date">
                <input type="date" className={inputCls}
                       value={form.closeDate ?? ''}
                       onChange={(e) => update({ closeDate: e.target.value })} />
              </Field>
            </div>
          </Section>

          <Section icon={<FiMapPin className="h-3.5 w-3.5" />} title="Ship-from address" hint="optional">
            <div className="grid grid-cols-2 gap-2.5">
              <Field label="Name">
                <input className={inputCls} value={form.addressName ?? ''}
                       onChange={(e) => update({ addressName: e.target.value })} />
              </Field>
              <Field label="Address line 1">
                <input className={inputCls} value={form.addressLine1 ?? ''}
                       onChange={(e) => update({ addressLine1: e.target.value })} />
              </Field>
              <Field label="City">
                <input className={inputCls} value={form.city ?? ''}
                       onChange={(e) => update({ city: e.target.value })} />
              </Field>
              <Field label="State">
                <input className={inputCls} value={form.state ?? ''}
                       onChange={(e) => update({ state: e.target.value })} />
              </Field>
              <Field label="Postal code">
                <input className={inputCls} value={form.postalCode ?? ''}
                       onChange={(e) => update({ postalCode: e.target.value })} />
              </Field>
              <Field label="Country">
                <input className={inputCls} value={form.countryCode ?? ''}
                       onChange={(e) => update({ countryCode: e.target.value.toUpperCase() })}
                       maxLength={2} />
              </Field>
            </div>
          </Section>
          </div>

          <Section icon={<FiHash className="h-3.5 w-3.5" />}
                   title={`Tracking numbers (${parsedTracking.length})`} hint="prefilled">
            <textarea
              rows={4}
              className={`${inputCls} font-mono text-[11.5px]`}
              value={trackingText}
              onChange={(e) => setTrackingText(e.target.value)}
              placeholder="One tracking number per line…"
            />
            <p className="mt-1.5 text-[10.5px] text-[#9a8b70]">
              Edit to exclude any — or use <span className="font-semibold text-[#6b5c42]">Close whole day</span> to let the
              server gather every open {form.carrierCode} label for the date.
            </p>
          </Section>

          {result ? <ResultBanner result={result} /> : null}
        </div>

        {/* footer */}
        <div className="flex items-center justify-between gap-2 border-t border-[#eee6d6] px-5 py-3">
          <button type="button" disabled={!form.carrierCode || submitting}
                  onClick={() => void submitWholeDay()}
                  title={`Close every open ${form.carrierCode} label for ${form.closeDate} (ignores the list above)`}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-[#cdbf9f] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0] disabled:opacity-40">
            <FiCalendar className="h-3.5 w-3.5" />
            Close whole day
          </button>
          <div className="flex items-center gap-2">
            <button type="button" onClick={onClose}
                    className="inline-flex items-center rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#412d15] hover:bg-[#faf7f0]">
              Cancel
            </button>
            <button type="button" disabled={!canSubmit || submitting}
                    onClick={() => void submit()}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-40">
              <FiFileText className="h-3.5 w-3.5" />
              {submitting ? 'Manifesting…' : 'Close out selected'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function ResultBanner({ result }: { result: ManifestResponse }) {
  // FDX-G2 — prefer manifests[] rendering when the backend split by fleet.
  const isSplit = result.manifests && result.manifests.length > 0
  const hasFailed = result.failedToClassify && result.failedToClassify.length > 0

  if (result.status === 'MANIFESTED' && !isSplit && !hasFailed) {
    return <FlatManifestedBanner result={result} />
  }

  if (result.status === 'MANIFESTED' || result.status === 'PARTIAL') {
    return (
      <div className="space-y-2">
        {result.status === 'PARTIAL' ? (
          <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
            <p className="flex items-center gap-1.5 font-semibold">
              <FiAlertTriangle className="h-3.5 w-3.5" /> Partial manifest — check per-fleet detail below
            </p>
            <p className="mt-1">{result.message}</p>
          </div>
        ) : (
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] text-emerald-800">
            <p className="flex items-center gap-1.5 font-semibold">
              <FiCheckCircle className="h-3.5 w-3.5" /> Manifests confirmed
            </p>
            <p className="mt-1">{result.message}</p>
          </div>
        )}
        {isSplit
          ? result.manifests!.map((m) => (
              <FleetManifestCard key={`${m.fleet}-${m.manifestId ?? 'noid'}`}
                                 carrierCode={result.carrierCode} entry={m} />
            ))
          : null}
        {hasFailed ? <FailedToClassifyList trackings={result.failedToClassify!} /> : null}
      </div>
    )
  }

  if (result.status === 'EMPTY' || result.status === 'NOT_SUPPORTED') {
    return (
      <div className="space-y-2">
        <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0] px-3 py-2 text-[12px] text-[#5a4526]">
          <p className="flex items-center gap-1.5 font-semibold">
            <FiAlertCircle className="h-3.5 w-3.5" />
            {result.status === 'EMPTY' ? 'Nothing to close' : 'Close-out not supported'}
          </p>
          <p className="mt-1">{result.message}</p>
        </div>
        {hasFailed ? <FailedToClassifyList trackings={result.failedToClassify!} /> : null}
      </div>
    )
  }
  // ERROR — flat + optional failed list.
  return (
    <div className="space-y-2">
      <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
        <p className="flex items-center gap-1.5 font-semibold">
          <FiAlertCircle className="h-3.5 w-3.5" /> Manifest not created
        </p>
        <p className="mt-1">{result.message}</p>
      </div>
      {hasFailed ? <FailedToClassifyList trackings={result.failedToClassify!} /> : null}
    </div>
  )
}

/** Pre-FDX-G flat happy-path banner. */
function FlatManifestedBanner({ result }: { result: ManifestResponse }) {
  return (
    <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] text-emerald-800">
      <p className="flex items-center gap-1.5 font-semibold">
        <FiCheckCircle className="h-3.5 w-3.5" /> Manifest confirmed
      </p>
      <p className="mt-1 font-mono text-[11px]">
        {result.carrierCode} · {result.manifestId} · {result.trackingCount} shipment(s)
      </p>
      <p className="mt-1">{result.message}</p>
      <ManifestPdfLink url={result.manifestPdfUrl} base64={result.manifestPdfBase64}
                       filename={`${result.carrierCode}-${result.manifestId}.pdf`} />
    </div>
  )
}

/** FDX-G2 — one per-fleet manifest card inside a split response. */
function FleetManifestCard({ carrierCode, entry }: { carrierCode: string; entry: ManifestEntry }) {
  const isOk = entry.status === 'MANIFESTED'
  const tone = isOk
    ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
    : 'border-rose-200 bg-rose-50 text-rose-800'
  const badgeTone = entry.fleet === 'EXPRESS'
    ? 'bg-sky-100 text-sky-800 ring-sky-200'
    : 'bg-[#f4eede] text-[#412d15] ring-[#e3d9c4]'
  return (
    <div className={`rounded-xl border ${tone} px-3 py-2 text-[12px]`}>
      <p className="flex items-center gap-1.5 font-semibold">
        <FiTruck className="h-3.5 w-3.5" />
        <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide ring-1 ${badgeTone}`}>
          {entry.fleet}
        </span>
        <span>{isOk ? 'Manifest confirmed' : 'Fleet manifest failed'}</span>
      </p>
      <p className="mt-1 font-mono text-[11px]">
        {carrierCode} · {entry.manifestId ?? '—'} · {entry.trackingCount} shipment(s)
      </p>
      <p className="mt-1">{entry.message}</p>
      <ManifestPdfLink url={entry.manifestPdfUrl} base64={entry.manifestPdfBase64}
                       filename={`${carrierCode}-${entry.fleet}-${entry.manifestId ?? 'manifest'}.pdf`} />
    </div>
  )
}

/** Shared "open / download manifest PDF" link (URL preferred, base64 fallback). */
function ManifestPdfLink({ url, base64, filename }: { url: string | null; base64: string | null; filename: string }) {
  const cls = 'mt-2 inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-white/60 px-2.5 py-1 text-[11px] font-semibold text-emerald-800 hover:bg-white/80'
  if (url) {
    return <a href={url} target="_blank" rel="noreferrer" className={cls}><FiDownload className="h-3 w-3" /> Open manifest PDF</a>
  }
  if (base64) {
    return <a href={`data:application/pdf;base64,${base64}`} download={filename} className={cls}><FiDownload className="h-3 w-3" /> Download manifest PDF</a>
  }
  return null
}

/** FDX-G2 — trackings the classifier couldn't resolve via the mapping chain. */
function FailedToClassifyList({ trackings }: { trackings: string[] }) {
  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
      <p className="flex items-center gap-1.5 font-semibold">
        <FiAlertTriangle className="h-3.5 w-3.5" />
        Excluded — couldn't classify fleet ({trackings.length})
      </p>
      <p className="mt-1 text-[11px]">
        These weren't included because the classifier couldn't resolve their fleet via the Code Maps SHIPVIA
        chain. Fix the mapping and re-run.
      </p>
      <ul className="mt-2 max-h-40 overflow-y-auto rounded-lg border border-amber-200 bg-white/60 px-2 py-1.5 font-mono text-[11px]">
        {trackings.map((t, i) => (
          <li key={`${i}-${t}`}>{t || '(blank)'}</li>
        ))}
      </ul>
    </div>
  )
}

/** Icon-badged section card — shared look with the Schedule-pickup modal. */
function Section({ icon, title, hint, className = '', children }: { icon: ReactNode; title: string; hint?: string; className?: string; children: ReactNode }) {
  return (
    <section className={`overflow-hidden rounded-xl border border-[#eee6d6] bg-white ${className}`}>
      <div className="flex items-center gap-2 border-b border-[#f3ecdd] bg-[#faf7f0]/60 px-3 py-2">
        <span className="inline-flex h-6 w-6 items-center justify-center rounded-lg bg-[#f4eede] text-[#412d15]">{icon}</span>
        <h4 className="text-[12px] font-semibold text-[#1f150c]">{title}</h4>
        {hint ? <span className="ml-auto text-[10px] font-semibold uppercase tracking-wide text-[#b6a684]">{hint}</span> : null}
      </div>
      <div className="p-3">{children}</div>
    </section>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-0.5 block text-[10.5px] font-semibold text-[#5a4526]">{label}</span>
      {children}
    </label>
  )
}
