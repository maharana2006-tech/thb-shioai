/**
 * Country-driven input helpers for address forms: the ITU phone dial code and a
 * human example of the postal-code format. Keyed by ISO-3166 alpha-2.
 *
 * Selecting a country auto-fills the phone country code and swaps the postal
 * placeholder to that country's format. Postal *validation* lives in
 * shipmentSchema.ts (POSTAL_RULES) — keep the examples here consistent with it.
 */

/** ISO alpha-2 → ITU calling code (digits only, no leading +). */
export const DIAL_CODES: Record<string, string> = {
  US: '1', CA: '1', BS: '1', BB: '1', JM: '1', TT: '1', DO: '1', PR: '1',
  GB: '44', IE: '353', FR: '33', DE: '49', ES: '34', PT: '351', IT: '39',
  NL: '31', BE: '32', LU: '352', CH: '41', AT: '43', DK: '45', SE: '46',
  NO: '47', FI: '358', IS: '354', PL: '48', CZ: '420', SK: '421', HU: '36',
  RO: '40', BG: '359', GR: '30', HR: '385', SI: '386', RS: '381', UA: '380',
  RU: '7', KZ: '7', TR: '90', EE: '372', LV: '371', LT: '370', MT: '356',
  CY: '357',
  CN: '86', HK: '852', MO: '853', TW: '886', JP: '81', KR: '82', KP: '850',
  IN: '91', PK: '92', BD: '880', LK: '94', NP: '977', SG: '65', MY: '60',
  ID: '62', TH: '66', VN: '84', PH: '63', KH: '855', LA: '856', MM: '95',
  AE: '971', SA: '966', QA: '974', KW: '965', BH: '973', OM: '968', JO: '962',
  LB: '961', IL: '972', IQ: '964', IR: '98',
  AU: '61', NZ: '64', FJ: '679', PG: '675',
  MX: '52', BR: '55', AR: '54', CL: '56', CO: '57', PE: '51', VE: '58',
  EC: '593', UY: '598', PY: '595', BO: '591', GT: '502', CR: '506', PA: '507',
  ZA: '27', EG: '20', MA: '212', DZ: '213', TN: '216', NG: '234', KE: '254',
  GH: '233', ET: '251', TZ: '255', UG: '256', SN: '221', CI: '225',
}

/** ISO alpha-2 → placeholder illustrating the postal-code format. */
export const POSTAL_EXAMPLES: Record<string, string> = {
  US: '14201', CA: 'K1A 0B1', GB: 'SW1A 1AA', IE: 'D02 AF30', IN: '110001',
  AU: '2000', DE: '10115', FR: '75001', ES: '28001', IT: '00100', NL: '1011 AB',
  BE: '1000', CH: '8001', AT: '1010', SE: '111 22', NO: '0150', DK: '1050',
  FI: '00100', PL: '00-001', PT: '1000-001', JP: '100-0001', CN: '100000',
  KR: '03187', SG: '188770', HK: '', MY: '50000', TH: '10200', BR: '01310-100',
  MX: '01000', AR: 'C1000', ZA: '0001', AE: '', RU: '101000', TR: '34000',
  NZ: '6011', ID: '10110', PH: '1000', VN: '100000',
}

const GENERIC_POSTAL_PLACEHOLDER = 'Postal / ZIP code'

/**
 * Expected NATIONAL phone-number digit count per country as [min, max] — i.e.
 * the digits WITHOUT the country/dial code (which lives in its own field).
 * Countries not listed fall back to a generic 7–15 digit rule. These are the
 * national-significant-number lengths (e.g. US/CA = 10, IN = 10, GB = 9–10).
 */
export const PHONE_DIGITS: Record<string, [number, number]> = {
  US: [10, 10], CA: [10, 10], GB: [9, 10], IE: [7, 9], IN: [10, 10],
  AU: [9, 9], NZ: [8, 10], DE: [6, 11], FR: [9, 9], ES: [9, 9],
  IT: [9, 11], NL: [9, 9], BE: [8, 9], CH: [9, 9], AT: [10, 11],
  SE: [7, 9], NO: [8, 8], DK: [8, 8], FI: [9, 10], PL: [9, 9],
  PT: [9, 9], JP: [9, 10], CN: [11, 11], KR: [9, 10], SG: [8, 8],
  MY: [9, 10], TH: [9, 9], VN: [9, 10], PH: [10, 10], ID: [9, 12],
  AE: [8, 9], SA: [9, 9], BR: [10, 11], MX: [10, 10], AR: [10, 11],
  ZA: [9, 9], TR: [10, 10], RU: [10, 10],
}

const GENERIC_PHONE_DIGITS: [number, number] = [7, 15]

/** Dial code for a country, or '' when unknown. */
export function dialCodeFor(code?: string | null): string {
  return DIAL_CODES[(code || '').toUpperCase()] ?? ''
}

/** Short hint of the expected phone length for a country (''=no rule). */
export function phoneHintFor(code?: string | null): string {
  const r = PHONE_DIGITS[(code || '').toUpperCase()]
  if (!r) return ''
  const [min, max] = r
  return min === max ? `${min} digits` : `${min}–${max} digits`
}

/**
 * Validate a phone number's NATIONAL digit count against the destination
 * country's expected length. Returns `null` when the number is acceptable (or
 * blank), or a human-readable message when the digit count is wrong for the
 * country. If the operator pasted the country code inline (e.g. "1 650 555
 * 0123"), it is stripped before counting so the national number is measured.
 */
export function phoneErrorFor(code?: string | null, phone?: string | null): string | null {
  const raw = (phone ?? '').trim()
  if (!raw) return null // blank is handled by required() where the field is mandatory
  const country = (code || '').toUpperCase()
  let national = raw.replace(/\D/g, '')
  if (!national) return 'Enter a phone number'
  const [min, max] = PHONE_DIGITS[country] ?? GENERIC_PHONE_DIGITS
  // Strip an inline dial code only when doing so brings an over-long number
  // into range — never turn a valid national number invalid.
  const dial = dialCodeFor(country)
  if (dial && national.length > max && national.startsWith(dial)) {
    const stripped = national.slice(dial.length)
    if (stripped.length >= min && stripped.length <= max) national = stripped
  }
  if (national.length < min || national.length > max) {
    const label = country || 'This country'
    return min === max
      ? `${label} phone numbers are ${min} digits (you entered ${national.length}).`
      : `${label} phone numbers are ${min}–${max} digits (you entered ${national.length}).`
  }
  return null
}

/**
 * Placeholder for the postal-code field. Countries with no postal system
 * (e.g. AE, HK) get an explicit "no postal code" hint.
 */
export function postalPlaceholderFor(code?: string | null): string {
  const key = (code || '').toUpperCase()
  if (key in POSTAL_EXAMPLES) {
    const ex = POSTAL_EXAMPLES[key]
    return ex ? `e.g. ${ex}` : 'No postal code required'
  }
  return GENERIC_POSTAL_PLACEHOLDER
}
