import * as Yup from 'yup'
import { COUNTRIES } from '../../utils/countries'
import { STATE_CODE_SETS } from '../../utils/stateCodes'
import { phoneErrorFor } from '../../utils/countryFormats'

/** Real ISO-3166 alpha-2 codes — so 'ZZ' fails membership, not just shape. */
const VALID_COUNTRIES = new Set(COUNTRIES.map((c) => c.code.toUpperCase()))

/**
 * Manual "New Shipment" validation. Mirrors what carriers (UPS/FedEx/USPS/DHL)
 * reject on the wire: required parties, country-aware postal codes, positive
 * weight/dimensions, and — for cross-border lanes — commercial-invoice items.
 *
 * Conditional rules key off two context booleans carried in the values:
 *   isInternational   → sender.country ≠ recipient.country
 *   isCustomPackage   → the "Custom package…" option is picked
 */

/** Country-specific postal-code patterns; anything else uses the generic rule. */
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
  AE: /^.{0,12}$/, // UAE has no postal system — accept blank/short
}
const GENERIC_POSTAL = /^[A-Za-z0-9][A-Za-z0-9 -]{1,10}$/

/** States/provinces are required for these countries (matches the import gate). */
const STATE_REQUIRED = new Set(['US', 'CA', 'AU', 'IN', 'BR', 'MX'])

// Unicode-aware: \p{L} accepts accented/international letters (é, ü, ñ, ç, …)
// and \p{M} the combining marks that follow them; the `u` flag enables both.
const NAME_RE = /^[\p{L}\p{M}0-9\s.,'&/-]+$/u
// Free-text address fields (street, city, state, company) accept any language
// but must not carry HTML-dangerous angle brackets — a light XSS guard for the
// places this value is later rendered (invoices, labels, tracking pages).
const SAFE_TEXT_RE = /^[^<>]*$/
const SAFE_TEXT_MSG = 'Cannot contain < or >'
const PHONE_RE = /^[0-9()+\-.\s]{7,20}$/
const HS_RE = /^\d{4}\.?\d{2}\.?\d{0,4}$/
const ISO2_RE = /^[A-Za-z]{2}$/

/**
 * Minimum HS/tariff-code digit count the DESTINATION (importing) country expects
 * on the commercial invoice. The first 6 digits are the international Harmonized
 * System; importing countries extend it (US HTS = 10, EU Combined Nomenclature =
 * 8, etc.). Countries not listed fall back to the 6-digit HS minimum.
 */
const HS_MIN_DIGITS: Record<string, number> = {
  US: 10, CA: 10,
  GB: 8, IE: 8, DE: 8, FR: 8, IT: 8, ES: 8, NL: 8, BE: 8, LU: 8, AT: 8,
  PT: 8, DK: 8, SE: 8, FI: 8, PL: 8, CZ: 8, HU: 8, RO: 8, GR: 8,
  IN: 8, CN: 8, AU: 8, MX: 8, BR: 8, ZA: 8, JP: 9, KR: 10, SG: 8,
}
const HS_DEFAULT_MIN = 6
/** Count the numeric digits in an HS code (ignoring dots/spaces). */
const hsDigits = (v?: string | null) => (v ?? '').replace(/\D/g, '').length

/** '' → undefined so `.required()` fires instead of a NaN/type error. */
const emptyToUndef = (_v: unknown, orig: unknown) =>
  orig === '' || orig === null ? undefined : _v

export const addressSchema = Yup.object({
  name: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('Name is required')
    .min(2, 'At least 2 characters')
    .max(35, 'Max 35 characters')
    .matches(NAME_RE, "Only letters, numbers, spaces and . , ' & / - allowed"),
  company: Yup.string()
    .max(35, 'Max 35 characters')
    .matches(SAFE_TEXT_RE, { excludeEmptyString: true, message: SAFE_TEXT_MSG })
    .nullable(),
  addressLine1: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('Street address is required')
    .min(2, 'At least 2 characters')
    .max(35, 'Max 35 characters')
    .matches(SAFE_TEXT_RE, { excludeEmptyString: true, message: SAFE_TEXT_MSG }),
  addressLine2: Yup.string()
    .max(35, 'Max 35 characters')
    .matches(SAFE_TEXT_RE, { excludeEmptyString: true, message: SAFE_TEXT_MSG })
    .nullable(),
  city: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('City is required')
    .min(2, 'At least 2 characters')
    .max(30, 'Max 30 characters')
    .matches(SAFE_TEXT_RE, { excludeEmptyString: true, message: SAFE_TEXT_MSG }),
  countryCode: Yup.string()
    .required('Country is required')
    .matches(ISO2_RE, 'Pick a country')
    .test('country-iso', 'Not a recognized country', (v) => !v || VALID_COUNTRIES.has(v.toUpperCase())),
  state: Yup.string()
    .nullable()
    .matches(SAFE_TEXT_RE, { excludeEmptyString: true, message: SAFE_TEXT_MSG })
    .when('countryCode', {
      is: (c: string) => STATE_REQUIRED.has((c || '').toUpperCase()),
      then: (s) =>
        s
          .required('State / region is required')
          .test('state-code', 'Not a valid state/province code', function (value) {
            const country = ((this.parent as { countryCode?: string }).countryCode || '').toUpperCase()
            const set = STATE_CODE_SETS[country]
            // US/CA/AU must be a real code; IN/BR/MX just require a value.
            if (!set) return true
            return !!value && set.has(value.trim().toUpperCase())
          }),
      otherwise: (s) => s.max(35, 'Max 35 characters'),
    }),
  postalCode: Yup.string()
    .required('Postal code is required')
    .matches(SAFE_TEXT_RE, { excludeEmptyString: true, message: SAFE_TEXT_MSG })
    .test('postal', 'Invalid postal code for this country', function (value) {
      if (!value) return false
      const country = ((this.parent as { countryCode?: string }).countryCode || '').toUpperCase()
      const rule = POSTAL_RULES[country] ?? GENERIC_POSTAL
      return rule.test(value.trim())
    }),
  phoneCountryCode: Yup.string()
    .nullable()
    .matches(/^\d{1,4}$/, { excludeEmptyString: true, message: 'Digits only, no +' }),
  phone: Yup.string()
    .nullable()
    .matches(PHONE_RE, { excludeEmptyString: true, message: 'Enter a valid phone number' })
    // Country-aware length: the national number's digit count must match the
    // destination country (e.g. US = 10, IN = 10, GB = 9–10). Carriers reject a
    // wrong-length phone on the rate/label call, so catch it up front.
    .test('phone-country-length', 'Phone length does not match the country', function (value) {
      const country = (this.parent as { countryCode?: string }).countryCode || ''
      const err = phoneErrorFor(country, value)
      return err ? this.createError({ message: err }) : true
    }),
  email: Yup.string().nullable().email('Enter a valid email').max(100, 'Max 100 characters'),
})

// Applied ONLY to international shipments (see shipmentSchema.items.when), so
// every field here is mandatory for the commercial invoice / customs clearance.
export const itemSchema = Yup.object({
  // Destination country rides on each item (mirrored from the page) so the
  // HS-code rule can require the digit length the importing country expects.
  destCountry: Yup.string().nullable(),
  description: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('Description is required')
    .min(2, 'At least 2 characters')
    .max(50, 'Max 50 characters'),
  sku: Yup.string().max(40, 'Max 40 characters').nullable(),
  hsCode: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('HS code is required for customs')
    .matches(HS_RE, 'HS code must be digits (e.g. 6109.10), 6–10 long')
    // Country-wise length: the importing country sets the minimum (US=10, EU=8…).
    .test('hs-country-length', function (value) {
      const digits = hsDigits(value)
      if (digits < 6) return true // the format rule already reports "too short"
      const dest = ((this.parent as { destCountry?: string }).destCountry || '').toUpperCase()
      const min = HS_MIN_DIGITS[dest] ?? HS_DEFAULT_MIN
      if (digits >= min) return true
      const article = min === 8 ? 'an' : 'a' // "an 8-digit", "a 10-digit"
      return this.createError({
        message: `${dest || 'This destination'} needs at least ${article} ${min}-digit HS code (you entered ${digits}).`,
      })
    }),
  countryOfOrigin: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('Country of origin is required')
    .matches(ISO2_RE, 'Use a 2-letter country code')
    .test('coo-iso', 'Not a recognized country', (v) => !v || VALID_COUNTRIES.has(v.toUpperCase())),
  quantity: Yup.number()
    .transform(emptyToUndef)
    .typeError('Quantity must be a number')
    .required('Qty required')
    .integer('Whole number')
    .min(1, 'At least 1')
    .max(100000, 'Quantity looks too high'),
  unitValue: Yup.number()
    .transform(emptyToUndef)
    .typeError('Unit value must be a number')
    .required('Value required')
    .moreThan(0, 'Must be greater than 0')
    .test('max-2-decimals', 'At most 2 decimal places', (v) =>
      v == null || Math.abs(v * 100 - Math.round(v * 100)) < 1e-9,
    ),
})

export const shipmentSchema = Yup.object({
  // context flags (mirrored from the page so `.when` can branch)
  isInternational: Yup.boolean(),
  isCustomPackage: Yup.boolean(),

  // Every manual shipment must be booked against a client (drives ship-from,
  // account resolution, allowed services and billing).
  clientCode: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('Select a client'),
  carrier: Yup.string().required('Pick a carrier'),
  account: Yup.string()
    .transform((v) => (typeof v === 'string' ? v.trim() : v))
    .required('Bill-to account is required'),
  incoterms: Yup.string().when('isInternational', {
    is: true,
    then: (s) => s.required('Incoterm is required').oneOf(['DAP', 'DDP'], 'Choose DAP or DDP'),
    otherwise: (s) => s.nullable(),
  }),
  reasonForExport: Yup.string().when('isInternational', {
    is: true,
    then: (s) => s.required('Reason for export is required'),
    otherwise: (s) => s.nullable(),
  }),
  currency: Yup.string().when('isInternational', {
    is: true,
    then: (s) => s.required('Currency is required').length(3, '3-letter currency code'),
    otherwise: (s) => s.nullable(),
  }),

  sender: addressSchema,
  recipient: addressSchema,

  weight: Yup.number()
    .transform(emptyToUndef)
    .typeError('Weight must be a number')
    .required('Weight is required')
    .moreThan(0, 'Weight must be greater than 0')
    .max(150, 'Weight exceeds the 150 lb carrier limit'),
  declaredValue: Yup.number()
    .transform(emptyToUndef)
    .typeError('Declared value must be a number')
    .min(0, 'Cannot be negative')
    .when('isInternational', {
      is: true,
      then: (s) => s.required('Declared value is required').moreThan(0, 'Must be greater than 0'),
      otherwise: (s) => s.nullable(),
    }),
  insuredValue: Yup.number()
    .transform(emptyToUndef)
    .typeError('Insured value must be a number')
    .min(0, 'Cannot be negative')
    .nullable()
    .test('insured-le-declared', 'Insured value cannot exceed declared value', function (value) {
      const declared = (this.parent as { declaredValue?: number }).declaredValue
      if (value == null || declared == null) return true
      return value <= declared
    }),

  length: Yup.number().transform(emptyToUndef).typeError('Number').when('isCustomPackage', {
    is: true,
    then: (s) => s.required('Required').moreThan(0, 'Must be > 0'),
    otherwise: (s) => s.nullable(),
  }),
  width: Yup.number().transform(emptyToUndef).typeError('Number').when('isCustomPackage', {
    is: true,
    then: (s) => s.required('Required').moreThan(0, 'Must be > 0'),
    otherwise: (s) => s.nullable(),
  }),
  height: Yup.number().transform(emptyToUndef).typeError('Number').when('isCustomPackage', {
    is: true,
    then: (s) => s.required('Required').moreThan(0, 'Must be > 0'),
    otherwise: (s) => s.nullable(),
  }),

  // Commercial-invoice lines only matter cross-border. Domestic shipments
  // carry a default (often empty) item row that must NOT trip validation, so
  // per-item rules are attached only in the international branch.
  items: Yup.array().when('isInternational', {
    is: true,
    then: (s) => s.of(itemSchema).min(1, 'International shipments need at least one item'),
    otherwise: (s) => s.of(Yup.mixed()).notRequired(),
  }),
})

export type ShipmentFormValues = Yup.InferType<typeof shipmentSchema>
