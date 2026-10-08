import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { FiAlertCircle, FiCalendar, FiCheckCircle, FiMapPin, FiPackage, FiTruck, FiX } from 'react-icons/fi'
import { pickupService, type PickupRequest, type PickupResponse } from '../../api/pickupService'
import { settingsPaths } from '../../routes/workspaceRoutes'
import { notify } from '../../utils/notify'
import {
  FIELD_LIMITS,
  hasErrors,
  validateCountry,
  validateLength,
  validatePhoneForCountry,
  validateZip,
} from '../../utils/clientValidation'
import { useModalDismiss } from '../../hooks/useModalDismiss'
import { useConnectedCarriers } from '../../hooks/useConnectedCarriers'

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
  { code: 'UPS', name: 'UPS' },
  { code: 'FEDEX', name: 'FedEx' },
  { code: 'USPS', name: 'USPS' },
  { code: 'DHL', name: 'DHL' },
] as const

const SERVICE_TYPES = [
  { value: 'GROUND', label: 'Ground' },
  { value: 'EXPRESS', label: 'Express' },
  { value: 'INTERNATIONAL', label: 'International' },
] as const

/** Segmented-pill button class (selected = espresso filled). */
const pillCls = (active: boolean) =>
  `inline-flex items-center justify-center gap-1.5 rounded-lg border px-2 py-2 text-[12px] font-semibold transition ${
    active
      ? 'border-[#1f150c] bg-[#1f150c] text-[#f4eede]'
      : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
  }`

/** Carriers won't book a pickup further out than this many days. */
const MAX_PICKUP_DAYS_AHEAD = 30
/** Sanity caps for the parcels section — mirrors typical carrier API limits. */
const MAX_PACKAGE_COUNT = 999
const MAX_TOTAL_WEIGHT = 9999
const NOTES_MAX = 255

type FieldKey =
  | 'carrierCode' | 'pickupDate' | 'pickupWindowStart' | 'pickupWindowEnd'
  | 'contactName' | 'contactPhone'
  | 'addressLine1' | 'addressLine2' | 'city' | 'state' | 'postalCode' | 'countryCode'
  | 'packageCount' | 'totalWeight' | 'specialInstructions'

export default function SchedulePickupModal({ onClose, defaults }: SchedulePickupModalProps) {
  // A11y audit — focus trap + Escape-to-close + focus restoration.
  const dialogRef = useRef<HTMLDivElement>(null)
  useModalDismiss(true, dialogRef, onClose)
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
    // FDX-F — Ground is the pre-FDX-F default; the operator can flip
    // to Express or International via the new picker in the modal.
    pickupServiceType: defaults?.pickupServiceType ?? 'GROUND',
  })
  const [result, setResult] = useState<PickupResponse | null>(null)
  /** Transport-level failure (network / 5xx) — rendered as an inline banner
   *  in the modal body, never as a popup. */
  const [apiError, setApiError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [touched, setTouched] = useState<Partial<Record<FieldKey, boolean>>>({})
  const [showAll, setShowAll] = useState(false)

  const update = (patch: Partial<PickupRequest>) => setForm((f) => ({ ...f, ...patch }))
  const touch = (k: FieldKey) => setTouched((t) => (t[k] ? t : { ...t, [k]: true }))

  // Show only carriers connected on this app (those with an active account);
  // null = unknown (loading / lookup failed) → fall back to all carriers.
  const connected = useConnectedCarriers()
  const carriers = connected && connected.size > 0
    ? CARRIERS.filter((c) => connected.has(c.code)) : [...CARRIERS]
  // If the default carrier isn't connected, select the first one that is.
  useEffect(() => {
    if (connected && connected.size > 0 && !connected.has(form.carrierCode) && carriers.length > 0) {
      update({ carrierCode: carriers[0].code })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected])

  /** Per-field errors — same validator set as the client wizard / warehouse
   *  modal so messages and limits stay consistent app-wide. */
  const errors = useMemo(() => {
    const e: Partial<Record<FieldKey, string>> = {}

    // --- Carrier + date -------------------------------------------------
    // Carrier is a fixed dropdown, but guard against a cleared/unknown value
    // (e.g. a stale default passed in from the parent).
    if (!form.carrierCode) {
      e.carrierCode = 'Carrier is required.'
    } else if (!CARRIERS.some((c) => c.code === form.carrierCode)) {
      e.carrierCode = 'Choose a supported carrier.'
    }
    const d = (form.pickupDate || '').trim()
    if (!d) {
      e.pickupDate = 'Pickup date is required.'
    } else if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) {
      e.pickupDate = 'Enter a valid date.'
    } else if (d < today) {
      e.pickupDate = 'Pickup date cannot be in the past.'
    } else {
      const horizon = new Date()
      horizon.setDate(horizon.getDate() + MAX_PICKUP_DAYS_AHEAD)
      if (d > horizon.toISOString().slice(0, 10)) {
        e.pickupDate = `Pickup date must be within ${MAX_PICKUP_DAYS_AHEAD} days.`
      }
    }
    const ws = form.pickupWindowStart ?? ''
    const we = form.pickupWindowEnd ?? ''
    if (!ws) e.pickupWindowStart = 'Window start is required.'
    if (!we) {
      e.pickupWindowEnd = 'Window end is required.'
    } else if (ws && we <= ws) {
      e.pickupWindowEnd = 'Window end must be after window start.'
    }

    // --- Pickup address -------------------------------------------------
    const nameErr = validateLength(form.contactName, FIELD_LIMITS.addr.name, 'Contact name', true, 2)
    if (nameErr) e.contactName = nameErr
    // Country-aware: length + shape rules follow the pickup address country,
    // and a `+` number must carry that country's calling code.
    const phoneErr = validatePhoneForCountry(form.contactPhone, form.countryCode, true)
    if (phoneErr) e.contactPhone = phoneErr
    const line1Err = validateLength(form.addressLine1, FIELD_LIMITS.addr.line1, 'Street address', true, 2)
    if (line1Err) e.addressLine1 = line1Err
    if (form.addressLine2) {
      const line2Err = validateLength(form.addressLine2, FIELD_LIMITS.addr.line2, 'Suite / unit', false)
      if (line2Err) e.addressLine2 = line2Err
    }
    const cityErr = validateLength(form.city, FIELD_LIMITS.addr.city, 'City', true, 2)
    if (cityErr) e.city = cityErr
    if (form.state) {
      const stateErr = validateLength(form.state, FIELD_LIMITS.addr.state, 'State / region', false)
      if (stateErr) e.state = stateErr
    }
    const countryErr = validateCountry(form.countryCode, true)
    if (countryErr) e.countryCode = countryErr
    const zipErr = validateZip(form.postalCode, form.countryCode, true)
    if (zipErr) e.postalCode = zipErr

    // --- Parcels --------------------------------------------------------
    if (!Number.isInteger(form.packageCount) || form.packageCount < 1) {
      e.packageCount = 'Package count must be a whole number of 1 or more.'
    } else if (form.packageCount > MAX_PACKAGE_COUNT) {
      e.packageCount = `Package count must be ${MAX_PACKAGE_COUNT} or fewer.`
    }
    if (!Number.isFinite(form.totalWeight) || form.totalWeight <= 0) {
      e.totalWeight = 'Total weight must be greater than 0.'
    } else if (form.totalWeight > MAX_TOTAL_WEIGHT) {
      e.totalWeight = `Total weight must be ${MAX_TOTAL_WEIGHT} or less.`
    }
    if (form.specialInstructions) {
      const notesErr = validateLength(form.specialInstructions, NOTES_MAX, 'Notes', false)
      if (notesErr) e.specialInstructions = notesErr
    }
    return e
  }, [form, today])

  /** Gate error display behind touched/showAll so a fresh modal isn't a wall
   *  of red — same convention as the warehouse / customs modals. */
  const err = (k: FieldKey): string | null =>
    (showAll || touched[k]) ? (errors[k] ?? null) : null

  const inputCls = (k?: FieldKey) => {
    const bad = k ? err(k) != null : false
    return `w-full rounded-lg border bg-white px-2.5 py-1.5 text-[12.5px] text-[#1f150c] outline-none focus:ring-1 ${
      bad
        ? 'border-rose-400 focus:border-rose-500 focus:ring-rose-400'
        : 'border-[#e3d9c4] focus:border-[#412d15] focus:ring-[#412d15]'
    }`
  }

  const submit = async () => {
    setShowAll(true)
    if (hasErrors(errors)) {
      // No popup — the inline messages under each input are the only error
      // surface. Just bring the first offending field into view.
      requestAnimationFrame(() => {
        document
          .querySelector('[aria-label="Schedule courier pickup"] [data-field-error]')
          ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      })
      return
    }
    setSubmitting(true)
    setResult(null)
    setApiError(null)
    try {
      const response = await pickupService.schedule(form)
      setResult(response.data ?? null)
      // Toast + inline banner, matching the Settings pickup form.
      if (response.data?.status === 'SCHEDULED') {
        notify.success(`Pickup scheduled — ${response.data.confirmationNumber}`)
      } else if (response.data?.status === 'NOT_SUPPORTED') {
        notify.info(`Pickup not scheduled — ${response.data.message}`)
      } else if (response.data) {
        notify.error(`Pickup not scheduled — ${response.data.message}`)
      }
    } catch (e) {
      setApiError(e instanceof Error ? e.message : 'Pickup call failed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Schedule courier pickup"
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[94vh] w-full max-w-[860px] flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-[0_30px_80px_rgba(31,21,12,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-[#eee6d6] px-5 py-4">
          <div className="flex items-start gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#1f150c] text-[#f4eede] shadow-sm">
              <FiTruck className="h-4 w-4" />
            </span>
            <div>
              <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-[#b6a684]">
                Pickup
              </p>
              <h3 className="mt-0.5 text-[15px] font-semibold text-[#1f150c]">Schedule courier pickup</h3>
              <p className="mt-1 text-[11.5px] text-[#6b5c42]">
                Books a driver to collect the parcels at your ship-from address.
              </p>
              <p className="mt-1 text-[11px] text-[#a1906d]">
                Manage pickups and end-of-day close in{' '}
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
          <Section icon={<FiCalendar className="h-3.5 w-3.5" />} title="Carrier & schedule">
            {/* Carrier — segmented pills instead of a dropdown. */}
            <span className="mb-1 block text-[10.5px] font-semibold text-[#5a4526]">Carrier <span className="text-rose-500">*</span></span>
            <div className="flex flex-wrap gap-2">
              {carriers.map((c) => (
                <button key={c.code} type="button"
                        onClick={() => { update({ carrierCode: c.code }); touch('carrierCode') }}
                        className={`${pillCls(form.carrierCode === c.code)} min-w-[76px] flex-1`}>
                  <FiTruck className="h-3.5 w-3.5" /> {c.name}
                </button>
              ))}
            </div>
            {err('carrierCode') ? <p data-field-error className="mt-1 text-[10.5px] text-rose-600">{err('carrierCode')}</p> : null}

            <div className="mt-3">
              <Field label="Pickup date" required error={err('pickupDate')}>
                <input type="date" className={inputCls('pickupDate')}
                       value={form.pickupDate}
                       min={today}
                       onChange={(e) => update({ pickupDate: e.target.value })}
                       onBlur={() => touch('pickupDate')} />
              </Field>
            </div>
            <div className="mt-2.5 grid grid-cols-2 gap-2.5">
              <Field label="Window start" required error={err('pickupWindowStart')}>
                <input type="time" className={inputCls('pickupWindowStart')}
                       value={form.pickupWindowStart ?? ''}
                       onChange={(e) => update({ pickupWindowStart: e.target.value })}
                       onBlur={() => touch('pickupWindowStart')} />
              </Field>
              <Field label="Window end" required error={err('pickupWindowEnd')}>
                <input type="time" className={inputCls('pickupWindowEnd')}
                       value={form.pickupWindowEnd ?? ''}
                       onChange={(e) => update({ pickupWindowEnd: e.target.value })}
                       onBlur={() => touch('pickupWindowEnd')} />
              </Field>
            </div>

            {/* Service — segmented pills. Determines the driver fleet (FedEx
                FDXE/FDXG · UPS 007/003); DHL/USPS accept but have one fleet.
                Undefined falls to GROUND (pre-FDX-F default). */}
            <span className="mb-1 mt-3 block text-[10.5px] font-semibold text-[#5a4526]">Service</span>
            <div className="grid grid-cols-3 gap-2">
              {SERVICE_TYPES.map((s) => (
                <button key={s.value} type="button"
                        onClick={() => update({ pickupServiceType: s.value })}
                        className={pillCls((form.pickupServiceType ?? 'GROUND') === s.value)}>
                  {s.label}
                </button>
              ))}
            </div>
          </Section>

          <Section icon={<FiMapPin className="h-3.5 w-3.5" />} title="Pickup address">
            <div className="grid grid-cols-2 gap-2.5">
              <Field label="Contact name" required error={err('contactName')}>
                <input className={inputCls('contactName')} value={form.contactName}
                       maxLength={FIELD_LIMITS.addr.name}
                       onChange={(e) => update({ contactName: e.target.value })}
                       onBlur={() => touch('contactName')} />
              </Field>
              <Field label="Contact phone" required error={err('contactPhone')}>
                <input className={inputCls('contactPhone')} value={form.contactPhone}
                       maxLength={FIELD_LIMITS.phone}
                       placeholder="+1 555 123 4567"
                       onChange={(e) => update({ contactPhone: e.target.value })}
                       onBlur={() => touch('contactPhone')} />
              </Field>
              <Field label="Address line 1" required className="col-span-2" error={err('addressLine1')}>
                <input className={inputCls('addressLine1')} value={form.addressLine1}
                       maxLength={FIELD_LIMITS.addr.line1}
                       onChange={(e) => update({ addressLine1: e.target.value })}
                       onBlur={() => touch('addressLine1')} />
              </Field>
              <Field label="Address line 2" className="col-span-2" error={err('addressLine2')}>
                <input className={inputCls('addressLine2')} value={form.addressLine2 ?? ''}
                       maxLength={FIELD_LIMITS.addr.line2}
                       onChange={(e) => update({ addressLine2: e.target.value })}
                       onBlur={() => touch('addressLine2')} />
              </Field>
              <Field label="City" required error={err('city')}>
                <input className={inputCls('city')} value={form.city}
                       maxLength={FIELD_LIMITS.addr.city}
                       onChange={(e) => update({ city: e.target.value })}
                       onBlur={() => touch('city')} />
              </Field>
              <Field label="State" error={err('state')}>
                <input className={inputCls('state')} value={form.state ?? ''}
                       maxLength={FIELD_LIMITS.addr.state}
                       onChange={(e) => update({ state: e.target.value })}
                       onBlur={() => touch('state')} />
              </Field>
              <Field label="Postal code" required error={err('postalCode')}>
                <input className={inputCls('postalCode')} value={form.postalCode}
                       maxLength={FIELD_LIMITS.addr.zip}
                       onChange={(e) => update({ postalCode: e.target.value })}
                       onBlur={() => touch('postalCode')} />
              </Field>
              <Field label="Country" required error={err('countryCode')}>
                <input className={inputCls('countryCode')} value={form.countryCode}
                       onChange={(e) => update({ countryCode: e.target.value.toUpperCase() })}
                       onBlur={() => touch('countryCode')}
                       maxLength={2} placeholder="US" />
              </Field>
            </div>
          </Section>
          </div>

          <Section icon={<FiPackage className="h-3.5 w-3.5" />} title="Parcels">
            <div className="grid grid-cols-3 gap-2.5">
              <Field label="Package count" required error={err('packageCount')}>
                <input type="number" min="1" max={MAX_PACKAGE_COUNT} step="1" className={inputCls('packageCount')}
                       value={form.packageCount}
                       onChange={(e) => update({ packageCount: Number(e.target.value) })}
                       onBlur={() => touch('packageCount')} />
              </Field>
              <Field label="Total weight" required error={err('totalWeight')}>
                <input type="number" min="0" max={MAX_TOTAL_WEIGHT} step="0.1" className={inputCls('totalWeight')}
                       value={form.totalWeight}
                       onChange={(e) => update({ totalWeight: Number(e.target.value) })}
                       onBlur={() => touch('totalWeight')} />
              </Field>
              <Field label="Unit">
                <select className={inputCls()} value={form.weightUnit ?? 'LB'}
                        onChange={(e) => update({ weightUnit: e.target.value as 'LB' | 'KG' })}>
                  <option value="LB">LB</option>
                  <option value="KG">KG</option>
                </select>
              </Field>
              {/* DHL-8 — per-package dims only matter to DHL's pickup routing
                  (van vs truck). Shown only for DHL so the form stays
                  uncluttered for the other carriers; DHL falls back to
                  30 × 20 × 10 cm when unset. */}
              {form.carrierCode === 'DHL' ? (
                <>
                  <Field label="Default length (per box)">
                    <input type="number" min="0" step="0.1"
                           className={inputCls()}
                           value={form.defaultLength ?? ''}
                           onChange={(e) => update({ defaultLength: e.target.value ? Number(e.target.value) : undefined })} />
                  </Field>
                  <Field label="Default width">
                    <input type="number" min="0" step="0.1"
                           className={inputCls()}
                           value={form.defaultWidth ?? ''}
                           onChange={(e) => update({ defaultWidth: e.target.value ? Number(e.target.value) : undefined })} />
                  </Field>
                  <Field label="Default height / Unit">
                    <div className="flex gap-1.5">
                      <input type="number" min="0" step="0.1"
                             className={`${inputCls()} min-w-0 flex-1`}
                             value={form.defaultHeight ?? ''}
                             onChange={(e) => update({ defaultHeight: e.target.value ? Number(e.target.value) : undefined })} />
                      <select className="w-16 shrink-0 rounded-lg border border-[#e3d9c4] bg-white px-1.5 py-1.5 text-[12.5px] text-[#1f150c] outline-none focus:border-[#412d15] focus:ring-1 focus:ring-[#412d15]"
                              value={form.dimUnit ?? 'CM'}
                              onChange={(e) => update({ dimUnit: e.target.value as 'CM' | 'IN' })}>
                        <option value="CM">CM</option>
                        <option value="IN">IN</option>
                      </select>
                    </div>
                  </Field>
                </>
              ) : null}
              <Field label="Notes for driver" className="col-span-3" error={err('specialInstructions')}>
                <textarea rows={2} className={inputCls('specialInstructions')}
                          maxLength={NOTES_MAX}
                          value={form.specialInstructions ?? ''}
                          onChange={(e) => update({ specialInstructions: e.target.value })}
                          onBlur={() => touch('specialInstructions')} />
              </Field>
            </div>
          </Section>

          {apiError || result ? (
            <div className="space-y-3">
              {apiError ? (
                <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
                  <p className="flex items-center gap-1.5 font-semibold">
                    <FiAlertCircle className="h-3.5 w-3.5" /> Request failed
                  </p>
                  <p className="mt-1">{apiError}</p>
                </div>
              ) : null}
              {result ? <ResultBanner result={result} /> : null}
            </div>
          ) : null}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-[#eee6d6] px-5 py-3">
          <button type="button" onClick={onClose}
                  className="inline-flex items-center rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#412d15] hover:bg-[#faf7f0]">
            Cancel
          </button>
          <button type="button" disabled={submitting}
                  onClick={() => void submit()}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:opacity-40">
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
      <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0] px-3 py-2 text-[12px] text-[#412d15]">
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

/** Icon-badged section card — shared look with the Close-out modal. */
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

function Field({
  label,
  required,
  className = '',
  error,
  children,
}: {
  label: string
  required?: boolean
  className?: string
  /** Inline validation error rendered under the control (null = valid). */
  error?: string | null
  children: React.ReactNode
}) {
  return (
    <label className={`block ${className}`}>
      <span className="mb-0.5 block text-[10.5px] font-semibold text-[#5a4526]">
        {label} {required ? <span className="text-rose-500">*</span> : null}
      </span>
      {children}
      {error ? <p data-field-error className="mt-1 text-[10.5px] leading-snug text-rose-600">{error}</p> : null}
    </label>
  )
}
