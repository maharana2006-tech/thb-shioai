/**
 * F5-C — recipient / shipper / notify / sold-to address block.
 * Extracted from NewShipmentPage so the same 220-line form
 * (Full name, Company, Address 1/2/3, Country, Phone, State, City,
 * Postal, Email, Residential-flag + Paste-and-autofill-with-AI
 * modal) lives in one place instead of inline in a 4.8K-LoC page.
 */
import { useState, type ReactNode } from 'react'
import { FiZap } from 'react-icons/fi'
import type { ManualShipmentAddress } from '../../api/orderService'
import { aiService } from '../../api/aiService'
import { notify } from '../../utils/notify'
import {
  dialCodeFor,
  phoneHintFor,
  postalCodeOptionalFor,
  postalPlaceholderFor,
} from '../../utils/countryFormats'
import { STATE_CODE_OPTIONS } from '../../utils/stateCodes'
import { Field, inputCls } from './_shared'
import { CountrySelect } from './CountrySelect'

export type AddressErrors = Partial<Record<keyof ManualShipmentAddress, string>>

export interface AddressBlockProps {
  value: ManualShipmentAddress
  onChange: (patch: Partial<ManualShipmentAddress>) => void
  withEmail?: boolean
  hideLine3?: boolean
  /** Extra control (e.g. address-book search) placed on the same line as "Paste & autofill with AI". */
  extraAction?: ReactNode
  /** Per-field validation messages (only passed after a submit attempt). */
  errors?: AddressErrors
}

export function AddressBlock({
  value,
  onChange,
  withEmail,
  hideLine3,
  extraAction,
  errors,
}: AddressBlockProps) {
  const [pasteOpen, setPasteOpen] = useState(false)
  const [pasteText, setPasteText] = useState('')
  const [parsing, setParsing] = useState(false)

  const runParse = async () => {
    if (!pasteText.trim()) return
    setParsing(true)
    try {
      const parsed = await aiService.parseAddress(pasteText)
      // Only apply the fields the model actually found — never clobber with blanks.
      const patch: Partial<ManualShipmentAddress> = {}
      const keys: (keyof ManualShipmentAddress)[] = [
        'name', 'company', 'phone', 'email', 'addressLine1', 'addressLine2',
        'city', 'state', 'postalCode', 'countryCode',
      ]
      let filled = 0
      for (const k of keys) {
        const v = (parsed as Record<string, unknown>)[k]
        if (typeof v === 'string' && v.trim()) {
          // Every key in `keys` is a string-valued field, but TS widens patch[k]
          // to the full value union — assign through a string-keyed view.
          ;(patch as Record<string, string>)[k] = v.trim()
          filled += 1
        }
      }
      if (!filled) {
        notify.error('Could not find an address in that text.')
        return
      }
      onChange(patch)
      notify.success(`Autofilled ${filled} field${filled === 1 ? '' : 's'} — please review.`)
      setPasteOpen(false)
      setPasteText('')
    } catch (err) {
      notify.apiError(err, 'AI autofill failed. Enter the address manually.')
    } finally {
      setParsing(false)
    }
  }

  return (
    <>
      <div className="mb-3">
        {pasteOpen ? (
          <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0] p-2.5">
            <textarea
              className={`${inputCls} min-h-[64px] resize-y`}
              value={pasteText}
              onChange={(e) => setPasteText(e.target.value)}
              placeholder="Paste an address, email signature, or order text — e.g. “Jane Doe, Acme Inc, 123 Market St Suite 400, Buffalo NY 14201, +1 212 555 0100”"
              autoFocus
            />
            <div className="mt-2 flex items-center gap-2">
              <button
                type="button"
                onClick={() => void runParse()}
                disabled={parsing || !pasteText.trim()}
                className="inline-flex items-center gap-1.5 rounded-lg bg-[#412d15] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#1f150c] disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiZap className="h-3.5 w-3.5" />
                {parsing ? 'Reading…' : 'Autofill fields'}
              </button>
              <button
                type="button"
                onClick={() => { setPasteOpen(false); setPasteText('') }}
                className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            {extraAction ? <div className="min-w-0 flex-1">{extraAction}</div> : null}
            <button
              type="button"
              onClick={() => setPasteOpen(true)}
              className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-dashed border-[#cdbf9f] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#412d15] hover:bg-[#faf7f0]"
            >
              <FiZap className="h-3.5 w-3.5" />
              Paste &amp; autofill with AI
            </button>
          </div>
        )}
      </div>
      <div className="grid grid-cols-2 gap-3">
      <Field label="Full name" required error={errors?.name} className="col-span-2 sm:col-span-1">
        <input className={inputCls} value={value.name} onChange={(e) => onChange({ name: e.target.value })} placeholder="Jane Doe" />
      </Field>
      <Field label="Company" error={errors?.company} className="col-span-2 sm:col-span-1">
        <input className={inputCls} value={value.company} onChange={(e) => onChange({ company: e.target.value })} placeholder="Acme Inc." />
      </Field>
      <Field label="Address line 1" required error={errors?.addressLine1} className="col-span-2">
        <input className={inputCls} value={value.addressLine1} onChange={(e) => onChange({ addressLine1: e.target.value })} placeholder="123 Market St" />
      </Field>
      <Field label="Address line 2" error={errors?.addressLine2} className="col-span-2">
        <input className={inputCls} value={value.addressLine2} onChange={(e) => onChange({ addressLine2: e.target.value })} placeholder="Suite 400" />
      </Field>
      {!hideLine3 && (value.addressLine2 || value.addressLine3) ? (
        <Field
          label="Address line 3"
          title="Needed for some JP / CN / IN addresses that span three street lines"
          className="col-span-2"
        >
          <input
            className={inputCls}
            value={value.addressLine3 ?? ''}
            onChange={(e) => onChange({ addressLine3: e.target.value })}
            placeholder="Chiyoda-ku, Nihonbashi"
          />
        </Field>
      ) : null}
      {/* Country FIRST — the state list and postal-code format both depend on
          it, so choosing the country up front makes those fields correct and
          prevents US/CA mismatches (e.g. a Canadian postal code under a US
          state). Order: Country → State → City → Postal. */}
      <div className="col-span-2 grid grid-cols-2 gap-3">
        <Field label="Country" required error={errors?.countryCode}>
          <CountrySelect
            value={value.countryCode}
            onChange={(code) => {
              // Country -> dial code is fully derived per operator ask
              // (2026-09-12): the "Phone country code" field is hidden
              // and phoneCountryCode always resyncs to the picked
              // country's dial. Blank when dialCodeFor returns nothing
              // (backend UpsConnector.joinPhone / equivalents already
              // omit the prefix in that case, so the phone field just
              // rides on the wire as-is).
              onChange({
                countryCode: code,
                phoneCountryCode: dialCodeFor(code) || '',
              })
            }}
          />
        </Field>
        <Field label="Phone" required error={errors?.phone} hint={phoneHintFor(value.countryCode) || undefined}>
          <input className={inputCls} value={value.phone} onChange={(e) => onChange({ phone: e.target.value })} placeholder="2125550100" />
        </Field>
      </div>
      <div className="col-span-2 grid grid-cols-3 gap-3">
        <Field label="State / region" error={errors?.state}>
          {/* Sprint 51 — for countries where carriers demand a real code
              (US / CA / AU), render a dropdown so operators can't type
              'Delaware' and get downstream rate/label rejection. Free-text
              input stays for every other country. The list keys off the
              country picked above, so pick the country first. */}
          {(() => {
            const options = STATE_CODE_OPTIONS[(value.countryCode || '').toUpperCase()]
            return options ? (
              <select
                className={inputCls}
                value={value.state}
                onChange={(e) => onChange({ state: e.target.value })}
              >
                <option value="">Select…</option>
                {options.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.code} — {s.label}
                  </option>
                ))}
              </select>
            ) : (
              <input
                className={inputCls}
                value={value.state}
                onChange={(e) => onChange({ state: e.target.value })}
                placeholder="NY"
              />
            )
          })()}
        </Field>
        <Field label="City" required error={errors?.city}>
          <input className={inputCls} value={value.city} onChange={(e) => onChange({ city: e.target.value })} placeholder="Buffalo" />
        </Field>
        {/* PR A — postal is optional for countries with no national
            postal system (HK, AE, etc.). Skip the asterisk and let a
            blank value pass the schema for those destinations. */}
        <Field label="Postal code" required={!postalCodeOptionalFor(value.countryCode)} error={errors?.postalCode}>
          <input className={inputCls} value={value.postalCode} onChange={(e) => onChange({ postalCode: e.target.value })} placeholder={postalPlaceholderFor(value.countryCode)} />
        </Field>
      </div>
      <div className="col-span-2 grid grid-cols-1 gap-3 sm:grid-cols-2 sm:items-end">
        {withEmail ? (
          <Field label="Email" error={errors?.email}>
            <input className={inputCls} value={value.email} onChange={(e) => onChange({ email: e.target.value })} placeholder="jane@acme.com" />
          </Field>
        ) : null}
        <label
          className={`mt-1 flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12px] font-semibold text-slate-700 ${withEmail ? '' : 'sm:col-span-2'}`}
          title="UPS + FedEx charge a residential surcharge on international to homes"
        >
          <input
            type="checkbox"
            checked={Boolean(value.residential)}
            onChange={(e) => onChange({ residential: e.target.checked })}
            className="h-4 w-4 shrink-0 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
          />
          <span>Residential address</span>
        </label>
      </div>
      </div>
    </>
  )
}
