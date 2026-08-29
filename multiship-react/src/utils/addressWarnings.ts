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

/**
 * Country-specific postal-code format patterns — a mirror of the manual-shipment
 * Yup schema (validation/yup/shipmentSchema). Kept here so the "Validate with
 * Carrier" banner can apply the SAME deterministic check the submit-time schema
 * does.
 */
const POSTAL_RULES: Record<string, RegExp> = {
  US: /^\d{5}(-\d{4})?$/,
  CA: /^[A-Za-z]\d[A-Za-z] ?\d[A-Za-z]\d$/,
  GB: /^[A-Za-z]{1,2}\d[A-Za-z\d]? ?\d[A-Za-z]{2}$/,
  IN: /^\d{6}$/,
  AU: /^\d{4}$/,
  DE: /^\d{5}$/,
  FR: /^\d{5}$/,
  JP: /^\d{3}-?\d{4}$/,
  CN: /^\d{6}$/,
  SG: /^\d{6}$/,
}

/**
 * A carrier can report a "match" even when the postal code cannot belong to the
 * destination country — the FedEx sandbox will "match" a US address carrying a
 * Canadian postal (M5H 2N2) and resolve the state to a Chilean region, yet
 * return attributes.Matched=true. Carrier Address Validation standardizes for
 * DELIVERABILITY; it does not vouch that a postal fits the country. So this
 * deterministic client-side check is the real gate.
 *
 * Stricter than {@link decorateWithStateWarning} on purpose: a bad state shape
 * still ships to the right place once corrected, but a postal that doesn't fit
 * the country means the carrier matched the WRONG location — so we downgrade a
 * falsely-green EXACT to attention and flip `valid` to false.
 */
export function decoratePostalWarning<
  T extends { warnings?: string[] | null; matchLevel?: string; valid?: boolean; message?: string } | null,
>(result: T, countryCode: string | null | undefined, postalCode: string | null | undefined): T {
  if (!result) return result
  const country = (countryCode || '').toUpperCase()
  const rule = POSTAL_RULES[country]
  const pc = (postalCode || '').trim()
  if (!rule || !pc) return result // no rule for this country, or nothing to check
  if (rule.test(pc)) return result // valid format for the country
  const warning = `"${postalCode}" isn't a valid ${country} postal-code format — the carrier may have matched a different location. Fix the postal code before shipping.`
  return {
    ...result,
    warnings: [...(result.warnings ?? []), warning],
    matchLevel: result.matchLevel === 'EXACT' ? 'AMBIGUOUS' : result.matchLevel,
    valid: false,
    // Replace the carrier's (now-misleading) "confirmed" text with the reason.
    message: warning,
  }
}
