import { useState } from 'react'
import { FiAlertCircle, FiCalendar, FiCheckCircle, FiTruck, FiX } from 'react-icons/fi'
import { pickupService, type PickupRequest, type PickupResponse } from '../../api/pickupService'
import { notify } from '../../utils/notify'

/**
 * Sprint 33 — schedule a courier pickup. Modal collects carrier, date,
 * time window, address, contact, and parcel count; hits POST /pickups
 * and displays the carrier's confirmation number on success.
 */
export interface SchedulePickupModalProps {
  onClose: () => void
  /** Optional pre-filled defaults from the parent (e.g. tenant address). */
  defaults?: Partial<PickupRequest>
}

const CARRIERS = [
  { code: 'UPS', label: 'UPS · PRN' },
  { code: 'FEDEX', label: 'FedEx · Pickup Confirmation' },
  { code: 'DHL', label: 'DHL · Dispatch Confirmation' },
  { code: 'USPS', label: 'USPS · Confirmation Number' },
] as const

const inputCls =
  'w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[12.5px] text-slate-950 outline-none focus:border-slate-950 focus:ring-1 focus:ring-slate-950'

export default function SchedulePickupModal({ onClose, defaults }: SchedulePickupModalProps) {
  const today = new Date().toISOString().slice(0, 10)
  const [form, setForm] = useState<PickupRequest>({
    carrierCode: defaults?.carrierCode ?? 'UPS',
    customerNo: defaults?.customerNo ?? null,
    pickupDate: defaults?.pickupDate ?? today,
    pickupWindowStart: defaults?.pickupWindowStart ?? '13:00',
    pickupWindowEnd: defaults?.pickupWindowEnd ?? '17:00',
    contactName: defaults?.contactName ?? '',
    contactPhone: defaults?.contactPhone ?? '',
    addressLine1: defaults?.addressLine1 ?? '',
    addressLine2: defaults?.addressLine2 ?? '',
    city: defaults?.city ?? '',
    state: defaults?.state ?? '',
    postalCode: defaults?.postalCode ?? '',
    countryCode: defaults?.countryCode ?? 'US',
    packageCount: defaults?.packageCount ?? 1,
    totalWeight: defaults?.totalWeight ?? 5,
    weightUnit: defaults?.weightUnit ?? 'LB',
    specialInstructions: defaults?.specialInstructions ?? '',
  })
  const [result, setResult] = useState<PickupResponse | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const update = (patch: Partial<PickupRequest>) => setForm((f) => ({ ...f, ...patch }))

  const canSubmit = Boolean(
    form.carrierCode && form.pickupDate && form.contactName && form.contactPhone
      && form.addressLine1 && form.city && form.postalCode && form.countryCode
      && form.packageCount > 0 && form.totalWeight > 0,
  )

  const submit = async () => {
    if (!canSubmit) return
    setSubmitting(true)
    setResult(null)
    try {
      const response = await pickupService.schedule(form)
      setResult(response.data ?? null)
      if (response.data?.status === 'SCHEDULED') {
        notify.success(`Pickup scheduled — ${response.data.confirmationNumber}`)
      } else if (response.data) {
        notify.error(response.data.message)
      }
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Pickup call failed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Schedule courier pickup"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(760px,92vh)] w-full max-w-[640px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-500">
              <FiTruck className="h-3 w-3" /> Pickup
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Schedule courier pickup
            </h3>
            <p className="mt-1 text-[11.5px] text-slate-500">
              Books a driver to collect the parcels at your ship-from address.
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
              <Field label="Pickup date" required>
                <div className="relative">
                  <input type="date" className={inputCls}
                         value={form.pickupDate}
                         onChange={(e) => update({ pickupDate: e.target.value })} />
                  <FiCalendar className="pointer-events-none absolute right-2 top-1/2 h-3 w-3 -translate-y-1/2 text-slate-400" />
                </div>
              </Field>
              <Field label="Window start">
                <input type="time" className={inputCls}
                       value={form.pickupWindowStart ?? ''}
                       onChange={(e) => update({ pickupWindowStart: e.target.value })} />
              </Field>
              <Field label="Window end">
                <input type="time" className={inputCls}
                       value={form.pickupWindowEnd ?? ''}
                       onChange={(e) => update({ pickupWindowEnd: e.target.value })} />
              </Field>
            </div>
          </Section>

          <Section title="Pickup address">
            <div className="grid grid-cols-2 gap-2">
              <Field label="Contact name" required>
                <input className={inputCls} value={form.contactName}
                       onChange={(e) => update({ contactName: e.target.value })} />
              </Field>
              <Field label="Contact phone" required>
                <input className={inputCls} value={form.contactPhone}
                       onChange={(e) => update({ contactPhone: e.target.value })} />
              </Field>
              <Field label="Address line 1" required className="col-span-2">
                <input className={inputCls} value={form.addressLine1}
                       onChange={(e) => update({ addressLine1: e.target.value })} />
              </Field>
              <Field label="Address line 2" className="col-span-2">
                <input className={inputCls} value={form.addressLine2 ?? ''}
                       onChange={(e) => update({ addressLine2: e.target.value })} />
              </Field>
              <Field label="City" required>
                <input className={inputCls} value={form.city}
                       onChange={(e) => update({ city: e.target.value })} />
              </Field>
              <Field label="State">
                <input className={inputCls} value={form.state ?? ''}
                       onChange={(e) => update({ state: e.target.value })} />
              </Field>
              <Field label="Postal code" required>
                <input className={inputCls} value={form.postalCode}
                       onChange={(e) => update({ postalCode: e.target.value })} />
              </Field>
              <Field label="Country" required>
                <input className={inputCls} value={form.countryCode}
                       onChange={(e) => update({ countryCode: e.target.value.toUpperCase() })}
                       maxLength={2} />
              </Field>
            </div>
          </Section>

          <Section title="Parcels">
            <div className="grid grid-cols-3 gap-2">
              <Field label="Package count" required>
                <input type="number" min="1" className={inputCls}
                       value={form.packageCount}
                       onChange={(e) => update({ packageCount: Number(e.target.value) })} />
              </Field>
              <Field label="Total weight" required>
                <input type="number" min="0" step="0.1" className={inputCls}
                       value={form.totalWeight}
                       onChange={(e) => update({ totalWeight: Number(e.target.value) })} />
              </Field>
              <Field label="Unit">
                <select className={inputCls} value={form.weightUnit ?? 'LB'}
                        onChange={(e) => update({ weightUnit: e.target.value as 'LB' | 'KG' })}>
                  <option value="LB">LB</option>
                  <option value="KG">KG</option>
                </select>
              </Field>
              <Field label="Notes for driver" className="col-span-3">
                <textarea rows={2} className={inputCls}
                          value={form.specialInstructions ?? ''}
                          onChange={(e) => update({ specialInstructions: e.target.value })} />
              </Field>
            </div>
          </Section>

          {result ? <ResultBanner result={result} /> : null}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button type="button" onClick={onClose}
                  className="inline-flex items-center rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50">
            Cancel
          </button>
          <button type="button" disabled={!canSubmit || submitting}
                  onClick={() => void submit()}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40">
            <FiTruck className="h-3 w-3" />
            {submitting ? 'Scheduling…' : 'Schedule pickup'}
          </button>
        </div>
      </div>
    </div>
  )
}

function ResultBanner({ result }: { result: PickupResponse }) {
  if (result.status === 'SCHEDULED') {
    return (
      <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] text-emerald-800">
        <p className="flex items-center gap-1.5 font-semibold">
          <FiCheckCircle className="h-3.5 w-3.5" /> Pickup confirmed
        </p>
        <p className="mt-1 font-mono text-[11px]">
          {result.carrierCode} · {result.confirmationNumber}
        </p>
        <p className="mt-1">{result.message}</p>
      </div>
    )
  }
  if (result.status === 'NOT_SUPPORTED') {
    return (
      <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12px] text-slate-700">
        <p className="font-semibold">Pickup not scheduled</p>
        <p className="mt-1">{result.message}</p>
      </div>
    )
  }
  return (
    <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
      <p className="flex items-center gap-1.5 font-semibold">
        <FiAlertCircle className="h-3.5 w-3.5" /> Carrier rejected the pickup
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

function Field({
  label,
  required,
  className = '',
  children,
}: {
  label: string
  required?: boolean
  className?: string
  children: React.ReactNode
}) {
  return (
    <label className={`block ${className}`}>
      <span className="mb-0.5 block text-[10.5px] font-semibold text-slate-600">
        {label} {required ? <span className="text-rose-500">*</span> : null}
      </span>
      {children}
    </label>
  )
}
