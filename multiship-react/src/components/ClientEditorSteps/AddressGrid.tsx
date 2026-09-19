/**
 * F5-B — shared address grid used by ShipFromStep + ReturnStep.
 * Extracted from ClientEditorPage so the two steps can import it
 * without dragging the whole page in.
 */
import type { Address } from '../../api/clientService'
import type { AddressLike } from '../../utils/clientValidation'
import type {
  AddressCaps,
  CarrierCode,
} from '../../utils/carrierFieldLimits'
import CountrySelect from '../workspace/CountrySelect'
import { Field, inputBaseClass, inputErr, inputOk } from './_shared'

export interface AddressGridProps {
  block: Address
  addressKey: 'shipFrom' | 'returnAddress'
  err: (k: keyof AddressLike) => string | null
  markTouched: (key: string) => void
  setAddr: (
    block: 'shipFrom' | 'returnAddress',
    key: keyof Address,
  ) => (e: { target: { value: string } }) => void
  caps: AddressCaps
  bindingHint: (field: keyof AddressCaps) => CarrierCode[]
}

export function AddressGrid({
  block,
  addressKey,
  err,
  markTouched,
  setAddr,
  caps,
  bindingHint,
}: AddressGridProps) {
  const a = block
  const cls = (k: keyof AddressLike) => `${inputBaseClass} ${err(k) ? inputErr : inputOk}`
  const country = (a.country || '').toUpperCase()
  const zipHint = country
    ? country === 'US' ? 'Format: 12345 or 12345-6789'
      : country === 'CA' ? 'Format: A1A 1A1'
      : country === 'GB' ? 'e.g. SW1A 1AA'
      : country === 'NL' ? 'e.g. 1012 AB'
      : country === 'JP' ? 'e.g. 100-0001'
      : country === 'BR' ? 'e.g. 01310-100'
      : undefined
    : undefined
  // Small "cap set by <carriers>" line under each capped input. Skipped
  // when no carrier is enabled yet — the caps degrade to loose DB defaults
  // in that case and the extra text is noise.
  const capHint = (field: keyof AddressCaps): string | undefined => {
    const carriers = bindingHint(field)
    if (carriers.length === 0) return undefined
    return `max ${caps[field]} chars · ${carriers.join(' / ')}`
  }
  const joinHint = (...parts: Array<string | undefined>) =>
    parts.filter((p): p is string => !!p && p.length > 0).join(' · ') || undefined
  return (
    <div className="grid grid-cols-3 gap-2">
      <div className="col-span-3">
        <Field label="Attention / company" required error={err('name')} hint={capHint('name')}>
          <input
            value={a.name ?? ''}
            onChange={setAddr(addressKey, 'name')}
            onBlur={() => markTouched(`${addressKey}.name`)}
            className={cls('name')}
            placeholder="Warehouse / contact name"
            maxLength={caps.name}
            autoComplete="organization"
            aria-invalid={err('name') ? true : undefined}
          />
        </Field>
      </div>
      <div className="col-span-2">
        <Field label="Street address" required error={err('line1')} hint={capHint('line')}>
          <input
            value={a.line1 ?? ''}
            onChange={setAddr(addressKey, 'line1')}
            onBlur={() => markTouched(`${addressKey}.line1`)}
            className={cls('line1')}
            placeholder="123 Industrial Blvd"
            maxLength={caps.line}
            autoComplete="address-line1"
            aria-invalid={err('line1') ? true : undefined}
          />
        </Field>
      </div>
      <Field label="Suite / unit" error={err('line2')} hint={capHint('line')}>
        <input
          value={a.line2 ?? ''}
          onChange={setAddr(addressKey, 'line2')}
          onBlur={() => markTouched(`${addressKey}.line2`)}
          className={cls('line2')}
          placeholder="Suite 400"
          maxLength={caps.line}
          autoComplete="address-line2"
          aria-invalid={err('line2') ? true : undefined}
        />
      </Field>
      <Field label="City" required error={err('city')} hint={capHint('city')}>
        <input
          value={a.city ?? ''}
          onChange={setAddr(addressKey, 'city')}
          onBlur={() => markTouched(`${addressKey}.city`)}
          className={cls('city')}
          placeholder="Chicago"
          maxLength={caps.city}
          autoComplete="address-level2"
          aria-invalid={err('city') ? true : undefined}
        />
      </Field>
      <Field label="State" required error={err('state')} hint={capHint('state')}>
        <input
          value={a.state ?? ''}
          onChange={setAddr(addressKey, 'state')}
          onBlur={() => markTouched(`${addressKey}.state`)}
          className={cls('state')}
          placeholder="IL"
          maxLength={caps.state}
          autoComplete="address-level1"
          aria-invalid={err('state') ? true : undefined}
        />
      </Field>
      <Field label="Zip" required error={err('zip')} hint={joinHint(zipHint, capHint('zip'))}>
        <input
          value={a.zip ?? ''}
          onChange={setAddr(addressKey, 'zip')}
          onBlur={() => markTouched(`${addressKey}.zip`)}
          className={cls('zip')}
          placeholder="60601"
          maxLength={caps.zip}
          autoComplete="postal-code"
          aria-invalid={err('zip') ? true : undefined}
        />
      </Field>
      <div className="col-span-2">
        <Field label="Country" required error={err('country')}>
          <CountrySelect
            value={a.country}
            onChange={(code) => {
              // Force ISO-2 into state — CountrySelect emits it uppercase but
              // legacy rows might carry lowercase; normalise regardless.
              setAddr(addressKey, 'country')({ target: { value: (code || '').toUpperCase() } })
              markTouched(`${addressKey}.country`)
            }}
            inputClassName={cls('country')}
          />
        </Field>
      </div>
      <Field label="Phone" error={err('phone')} hint={capHint('phone')}>
        <input
          type="tel"
          value={a.phone ?? ''}
          onChange={setAddr(addressKey, 'phone')}
          onBlur={() => markTouched(`${addressKey}.phone`)}
          className={cls('phone')}
          placeholder="+1 555-123-4567"
          maxLength={caps.phone}
          autoComplete="tel"
          inputMode="tel"
          aria-invalid={err('phone') ? true : undefined}
        />
      </Field>
    </div>
  )
}
