import { STATE_CODE_SETS } from './stateCodes'

/**
 * Sprint 51 polish — FedEx / UPS / DHL Address Validation APIs are lenient
 * normalizers: they accept a full state name like "Delaware" and report
 * Matched=true. But the same carriers' Rate / Ship APIs reject anything
 * that isn't a 2-letter state / province code. This decorator appends a
 * client-side warning to the banner when the user-entered state doesn't
 * match a valid code, so a green "confirmed" banner doesn't give false
 * confidence right before Compare rates / Generate label fail.
 *
 * Non-invasive — only appends to `warnings`. Never changes `valid` or
 * `matchLevel`, because the carrier really did confirm the underlying
 * address (city / street / postal). Only the state shape is off.
 */
export function decorateWithStateWarning<T extends { warnings?: string[] | null } | null>(
  result: T,
  countryCode: string | null | undefined,
  state: string | null | undefined,
): T {
  if (!result) return result
  const country = (countryCode || '').toUpperCase()
  const set = STATE_CODE_SETS[country]
  if (!set) return result // no dropdown for this country → no warning to add
  const code = (state || '').trim().toUpperCase()
  if (code && set.has(code)) return result // already a valid code
  const warning = code
    ? `"${state}" isn't a valid ${country} state / province code. Pick a 2-letter code (e.g. "DE") before shipping — carriers reject full names on the rate & label calls.`
    : `A ${country} state / province code is required for the rate & label calls. Pick one from the dropdown before shipping.`
  return {
    ...result,
    warnings: [...(result.warnings ?? []), warning],
  }
}
