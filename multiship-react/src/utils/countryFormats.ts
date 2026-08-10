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

/** Dial code for a country, or '' when unknown. */
export function dialCodeFor(code?: string | null): string {
  return DIAL_CODES[(code || '').toUpperCase()] ?? ''
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
