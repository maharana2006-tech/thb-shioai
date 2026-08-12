import { useState } from 'react'
import {
  FiAlertCircle,
  FiCheckCircle,
  FiDownload,
  FiFileText,
  FiX,
} from 'react-icons/fi'
import { manifestService, type ManifestRequest, type ManifestResponse } from '../../api/manifestService'
import { notify } from '../../utils/notify'

/**
 * Sprint 34 — end-of-day close-out modal. Operator picks a carrier,
 * confirms the tracking numbers to include (default = every non-empty
 * tracking number the parent supplied), and submits. Backend calls the
 * carrier's manifest endpoint and returns an ID + optional PDF.
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
  { code: 'UPS', label: 'UPS · End of Day' },
  { code: 'FEDEX', label: 'FedEx · CloseShipment' },
  { code: 'USPS', label: 'USPS · SCAN Form' },
  { code: 'DHL', label: 'DHL · (via pickup)' },
] as const

const inputCls =
  'w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[12.5px] text-slate-950 outline-none focus:border-slate-950 focus:ring-1 focus:ring-slate-950'

export default function CloseOutModal({ onClose, trackingNumbers, defaults }: CloseOutModalProps) {
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

  const parsedTracking = trackingText
    .split(/\s+/)
    .map((t) => t.trim())
    .filter((t) => t.length > 0)

  const canSubmit = Boolean(form.carrierCode && parsedTracking.length > 0)

  const submit = async () => {
    if (!canSubmit) return
    setSubmitting(true)
    setResult(null)
    try {
      const response = await manifestService.closeOut({ ...form, trackingNumbers: parsedTracking })
      setResult(response.data ?? null)
      if (response.data?.status === 'MANIFESTED') {
        notify.success(`Manifested ${response.data.trackingCount} shipment(s) · ${response.data.manifestId}`)
      } else if (response.data) {
        notify.error(response.data.message)
      }
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
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(720px,92vh)] w-full max-w-[640px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-500">
              <FiFileText className="h-3 w-3" /> Close out
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              End-of-day manifest
            </h3>
            <p className="mt-1 text-[11.5px] text-slate-500">
              Manifests today's tracking numbers so the driver can accept the parcels at pickup.
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

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
          <Section title="Carrier + date">
            <div className="grid grid-cols-2 gap-2">
              <Field label="Carrier">
                <select className={inputCls} value={form.carrierCode}
                        onChange={(e) => update({ carrierCode: e.target.value })}>
                  {CARRIERS.map((c) => (
                    <option key={c.code} value={c.code}>{c.label}</option>
                  ))}
                </select>
              </Field>
              <Field label="Close date">
                <input type="date" className={inputCls}
                       value={form.closeDate ?? ''}
                       onChange={(e) => update({ closeDate: e.target.value })} />
              </Field>
            </div>
            {form.carrierCode === 'DHL' ? (
              <p className="mt-1.5 text-[10.5px] text-slate-500">
                DHL manifests are implicit via the pickup request — no separate close-out call.
              </p>
            ) : null}
          </Section>

          <Section title={`Tracking numbers (${parsedTracking.length})`}>
            <textarea
              rows={6}
              className={`${inputCls} font-mono text-[11.5px]`}
              value={trackingText}
              onChange={(e) => setTrackingText(e.target.value)}
              placeholder="One tracking number per line…"
            />
            <p className="mt-1 text-[10.5px] text-slate-500">
              Backend prefills these from today's generated labels; edit to exclude any.
            </p>
          </Section>

          <Section title="Ship-from address (optional)">
            <div className="grid grid-cols-2 gap-2">
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

          {result ? <ResultBanner result={result} /> : null}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button type="button" onClick={onClose}
                  className="inline-flex items-center rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50">
            Close
          </button>
          <button type="button" disabled={!canSubmit || submitting}
                  onClick={() => void submit()}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40">
            <FiFileText className="h-3 w-3" />
            {submitting ? 'Manifesting…' : 'Close out'}
          </button>
        </div>
      </div>
    </div>
  )
}

function ResultBanner({ result }: { result: ManifestResponse }) {
  if (result.status === 'MANIFESTED') {
    return (
      <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] text-emerald-800">
        <p className="flex items-center gap-1.5 font-semibold">
          <FiCheckCircle className="h-3.5 w-3.5" /> Manifest confirmed
        </p>
        <p className="mt-1 font-mono text-[11px]">
          {result.carrierCode} · {result.manifestId} · {result.trackingCount} shipment(s)
        </p>
        <p className="mt-1">{result.message}</p>
        {result.manifestPdfUrl ? (
          <a href={result.manifestPdfUrl} target="_blank" rel="noreferrer"
             className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-white/60 px-2.5 py-1 text-[11px] font-semibold text-emerald-800 hover:bg-white/80">
            <FiDownload className="h-3 w-3" /> Open manifest PDF
          </a>
        ) : null}
        {result.manifestPdfBase64 && !result.manifestPdfUrl ? (
          <a
            href={`data:application/pdf;base64,${result.manifestPdfBase64}`}
            download={`${result.carrierCode}-${result.manifestId}.pdf`}
            className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-white/60 px-2.5 py-1 text-[11px] font-semibold text-emerald-800 hover:bg-white/80"
          >
            <FiDownload className="h-3 w-3" /> Download manifest PDF
          </a>
        ) : null}
      </div>
    )
  }
  if (result.status === 'NOT_SUPPORTED') {
    return (
      <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12px] text-slate-700">
        <p className="font-semibold">Close-out not supported</p>
        <p className="mt-1">{result.message}</p>
      </div>
    )
  }
  return (
    <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
      <p className="flex items-center gap-1.5 font-semibold">
        <FiAlertCircle className="h-3.5 w-3.5" /> Carrier rejected the manifest
      </p>
      <p className="mt-1">{result.message}</p>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h4 className="mb-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
        {title}
      </h4>
      {children}
    </section>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-0.5 block text-[10.5px] font-semibold text-slate-600">
        {label}
      </span>
      {children}
    </label>
  )
}
