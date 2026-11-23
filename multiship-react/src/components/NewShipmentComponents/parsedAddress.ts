/**
 * Turns the AI address parser's loose output into values the New shipment
 * form actually accepts: ISO country code (the model sometimes returns a
 * name), state *code* for countries with a dropdown (the model sometimes
 * returns "New York"), national phone digits with the dial code moved into
 * phoneCountryCode (otherwise the carrier gets "+1" twice), and no blanks
 * so existing form data is never clobbered.
 */
import type { ParsedAddress } from '../../api/aiService'
import type { ManualShipmentAddress } from '../../api/orderService'
import { COUNTRIES } from '../../utils/countries'
import { dialCodeFor } from '../../utils/countryFormats'
import { STATE_CODE_OPTIONS } from '../../utils/stateCodes'

const TEXT_KEYS = [
  'name', 'company', 'email', 'addressLine1', 'addressLine2', 'city', 'postalCode',
] as const

const COUNTRY_ALIASES: Record<string, string> = { USA: 'US', UK: 'GB', UAE: 'AE' }

function countryCodeFor(raw: string): string | undefined {
  const v = raw.trim().toUpperCase()
  if (!v) return undefined
  if (COUNTRY_ALIASES[v]) return COUNTRY_ALIASES[v]
  if (v.length === 2 && COUNTRIES.some((c) => c.code === v)) return v
  const byName = COUNTRIES.find((c) => c.name.toUpperCase() === v)
  return byName?.code ?? (v.length === 2 ? v : undefined)
}

function stateCodeFor(raw: string, country: string): string {
  const v = raw.trim()
  const options = STATE_CODE_OPTIONS[country]
  if (!options) return v
  const hit = options.find(
    (s) => s.code === v.toUpperCase() || s.label.toUpperCase() === v.toUpperCase(),
  )
  return hit ? hit.code : v
}

/** "+1 (212) 555-0100" with dial "1" → "2125550100"; strips "00"/"+" prefixes too. */
function nationalPhone(raw: string, dial: string): string {
  let digits = raw.replace(/\D/g, '')
  if (digits.startsWith('00')) digits = digits.slice(2)
  if (dial && digits.length > 10 && digits.startsWith(dial)) digits = digits.slice(dial.length)
  return digits
}

export interface NormalizedAddress {
  patch: Partial<ManualShipmentAddress>
  /** Fields the parser found. */
  filled: number
  /** Fields that already had a different value in the form. */
  replaced: number
}

export function normalizeParsedAddress(
  parsed: ParsedAddress,
  current: ManualShipmentAddress,
): NormalizedAddress {
  const patch: Partial<ManualShipmentAddress> = {}
  const str = (k: keyof ParsedAddress) => {
    const v = parsed[k]
    return typeof v === 'string' && v.trim() ? v.trim() : ''
  }
  for (const k of TEXT_KEYS) {
    const v = str(k)
    if (v) patch[k] = v
  }
  const country = countryCodeFor(str('countryCode')) ?? (current.countryCode || '').toUpperCase()
  if (str('countryCode') && country) {
    patch.countryCode = country
    patch.phoneCountryCode = dialCodeFor(country) || ''
  }
  const state = str('state')
  if (state) patch.state = stateCodeFor(state, country)
  const phone = str('phone')
  if (phone) patch.phone = nationalPhone(phone, dialCodeFor(country))

  const keys = Object.keys(patch).filter((k) => k !== 'phoneCountryCode') as (keyof ManualShipmentAddress)[]
  const replaced = keys.filter((k) => {
    const before = current[k]
    return typeof before === 'string' && before.trim() && before.trim() !== patch[k]
  }).length
  return { patch, filled: keys.length, replaced }
}
