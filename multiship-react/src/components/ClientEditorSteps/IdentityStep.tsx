/**
 * F5-B — Identity step of the client-editor wizard. Extracted from
 * ClientEditorPage so the ~190-line form section is testable in
 * isolation and no longer sits in the middle of a 3.5K-LoC file.
 * Behavior 1:1 with the pre-extraction inline function.
 */
import type { ClientUpsertPayload } from '../../api/clientService'
import { FIELD_LIMITS } from '../../utils/clientValidation'
import { TimezoneCombobox } from '../TimezoneCombobox'
import { Field, inputBaseClass, inputErr, inputOk } from './_shared'

export interface IdentityStepProps {
  form: ClientUpsertPayload
  isEdit: boolean
  errors: Record<string, string | null>
  checkingCode: boolean
  touched: Record<string, boolean>
  markTouched: (key: string) => void
  set: (key: keyof ClientUpsertPayload) => (e: { target: { value: string } }) => void
}

export function IdentityStep({
  form,
  isEdit,
  errors,
  checkingCode,
  touched,
  markTouched,
  set,
}: IdentityStepProps) {
  const err = (k: string) => (touched[`identity.${k}`] ? errors[k] || null : null)
  const codeHint = isEdit
    ? 'Immutable after create.'
    : checkingCode
      ? 'Checking availability…'
      : "Letters, digits, '-', '_'. Uppercase-normalized."
  return (
    <div className="px-4 py-3 space-y-3">
      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-4">
        <Field label="Client code" required error={err('clientCode')} hint={codeHint}>
          <input
            value={form.clientCode}
            onChange={set('clientCode')}
            onBlur={() => markTouched('identity.clientCode')}
            readOnly={isEdit}
            placeholder="MA1885"
            maxLength={FIELD_LIMITS.clientCode}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={err('clientCode') ? true : undefined}
            className={`${inputBaseClass} ${err('clientCode') ? inputErr : inputOk} ${isEdit ? 'opacity-70' : ''} uppercase`}
          />
        </Field>
        <Field label="Client name" required error={err('name')}>
          <input
            value={form.name}
            onChange={set('name')}
            onBlur={() => markTouched('identity.name')}
            placeholder="Modern Art Fabrics"
            maxLength={FIELD_LIMITS.name}
            aria-invalid={err('name') ? true : undefined}
            className={`${inputBaseClass} ${err('name') ? inputErr : inputOk}`}
          />
        </Field>
        <Field label="Email" error={err('email')}>
          <input
            type="email"
            value={form.email}
            onChange={set('email')}
            onBlur={() => markTouched('identity.email')}
            placeholder="contact@client.com"
            maxLength={FIELD_LIMITS.email}
            autoComplete="email"
            spellCheck={false}
            inputMode="email"
            aria-invalid={err('email') ? true : undefined}
            className={`${inputBaseClass} ${err('email') ? inputErr : inputOk}`}
          />
        </Field>
        <Field label="Phone" error={err('phone')}>
          <input
            type="tel"
            value={form.phone}
            onChange={set('phone')}
            onBlur={() => markTouched('identity.phone')}
            placeholder="+1 555-123-4567"
            maxLength={FIELD_LIMITS.phone}
            autoComplete="tel"
            inputMode="tel"
            aria-invalid={err('phone') ? true : undefined}
            className={`${inputBaseClass} ${err('phone') ? inputErr : inputOk}`}
          />
        </Field>
      </div>

      {/* Sprint 50 Tier 1 finding #4 — per-tenant defaults panel. All fields
          optional; the label/rate/customs pipelines fall back to platform
          hardcodes when NULL. Backend enforces ISO 4217 currency,
          {LB,KG,OZ,G} weight, {IN,CM,MM} dim, ISO-3166-1 alpha-2 country.
          Empty select value → undefined in the payload → NULL in the DB. */}
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <h4 className="text-[12px] font-semibold text-slate-950">Defaults</h4>
        <p className="text-[10.5px] text-slate-500">
          Applied when a shipment doesn't override. Leave any blank to use the platform default.
        </p>
        <div className="mt-2 grid grid-cols-1 gap-2.5 sm:grid-cols-5">
          <Field label="Currency">
            <select
              value={form.defaultCurrency || ''}
              onChange={set('defaultCurrency')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="USD">USD</option>
              <option value="EUR">EUR</option>
              <option value="GBP">GBP</option>
              <option value="CAD">CAD</option>
              <option value="AUD">AUD</option>
              <option value="INR">INR</option>
              <option value="JPY">JPY</option>
              <option value="CNY">CNY</option>
              <option value="MXN">MXN</option>
              <option value="AED">AED</option>
            </select>
          </Field>
          <Field label="Weight unit">
            <select
              value={form.defaultWeightUnit || ''}
              onChange={set('defaultWeightUnit')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="LB">LB (pounds)</option>
              <option value="KG">KG (kilograms)</option>
              <option value="OZ">OZ (ounces)</option>
              <option value="G">G (grams)</option>
            </select>
          </Field>
          <Field label="Dimension unit">
            <select
              value={form.defaultDimUnit || ''}
              onChange={set('defaultDimUnit')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="IN">IN (inches)</option>
              <option value="CM">CM (centimeters)</option>
              <option value="MM">MM (millimeters)</option>
            </select>
          </Field>
          <Field label="Origin country">
            <select
              value={form.defaultOriginCountry || ''}
              onChange={set('defaultOriginCountry')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="US">US — United States</option>
              <option value="CA">CA — Canada</option>
              <option value="MX">MX — Mexico</option>
              <option value="GB">GB — United Kingdom</option>
              <option value="DE">DE — Germany</option>
              <option value="FR">FR — France</option>
              <option value="IN">IN — India</option>
              <option value="CN">CN — China</option>
              <option value="AU">AU — Australia</option>
              <option value="JP">JP — Japan</option>
              <option value="AE">AE — UAE</option>
              <option value="SG">SG — Singapore</option>
            </select>
          </Field>
          <Field label="Timezone" hint="IANA (e.g. America/New_York)">
            <TimezoneCombobox
              value={form.timezone || ''}
              onChange={(v) => set('timezone')({ target: { value: v } })}
              className={`${inputBaseClass} ${inputOk}`}
            />
          </Field>
        </div>
      </div>
    </div>
  )
}
