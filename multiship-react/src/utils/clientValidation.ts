/**
 * Field validators for the client wizard. Return either `null` (valid) or a
 * short human-readable error string that the UI renders inline.
 *
 * The rules mirror what the backend enforces on ClientUpsertRequest /
 * AddressDTO (max lengths, client-code pattern) and add front-of-house checks
 * the backend skips (email format, phone shape, country-aware zip, live
 * client-code uniqueness). Keep in sync when either side changes.
 */

/**
 * Backend @Size caps — single source of truth for both validators AND the
 * `maxLength` attribute on inputs. Keep these numbers aligned with
 * ClientUpsertRequest / AddressDTO on the Java side; if either side moves,
 * this constant changes.
 */
export const FIELD_LIMITS = {
  clientCode: 50,
  name: 255,
  email: 255,
  phone: 50,
  addr: {
    name: 255,
    line1: 255,
    line2: 255,
    city: 100,
    state: 50,
    zip: 20,
    /** ISO-2 is 2 chars; backend column allows up to 10. We enforce ISO-2. */
    country: 2,
    phone: 50,
  },
} as const

/** Backend Pattern regex — [A-Za-z0-9_-]+. We uppercase-normalize before send. */
const CLIENT_CODE_RE = /^[A-Z0-9_-]+$/

/** Pragmatic email format — allows most valid RFC 5322 addresses without the
 *  full spec's edge cases (comments, quoted locals). Good enough for a form. */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** E.164-ish phone: optional leading +, then 7–15 digits with optional
 *  spaces / dashes / parens as separators. Rejects letters and stray symbols. */
const PHONE_RE = /^\+?[\d][\d\s\-().]{5,20}$/

/** ISO-2 country code — exactly two letters. */
const COUNTRY_RE = /^[A-Za-z]{2}$/

/** Country-aware zip patterns for the countries the app most commonly sees.
 *  When a country isn't in the map we fall back to a general "at least 3 chars
 *  alphanumeric" check so we don't block valid postcodes we haven't modelled. */
const ZIP_PATTERNS: Record<string, RegExp> = {
  US: /^\d{5}(-\d{4})?$/,
  CA: /^[A-Za-z]\d[A-Za-z][ -]?\d[A-Za-z]\d$/,
  GB: /^[A-Za-z]{1,2}\d[A-Za-z\d]?[ ]?\d[A-Za-z]{2}$/,
  DE: /^\d{5}$/,
  FR: /^\d{5}$/,
  IT: /^\d{5}$/,
  ES: /^\d{5}$/,
  NL: /^\d{4}\s?[A-Za-z]{2}$/,
  AU: /^\d{4}$/,
  IN: /^\d{6}$/,
  JP: /^\d{3}-?\d{4}$/,
  CN: /^\d{6}$/,
  BR: /^\d{5}-?\d{3}$/,
  MX: /^\d{5}$/,
  SG: /^\d{6}$/,
}

export function validateClientCode(value: string): string | null {
  const v = (value || '').trim().toUpperCase()
  if (!v) return 'Client code is required.'
  // Matches backend @Pattern("[A-Za-z0-9_-]+") — at least 1 char, no spaces,
  // only letters, digits, dashes, underscores. Uppercase-normalized on save.
  if (v.length > FIELD_LIMITS.clientCode) return `Client code must be ${FIELD_LIMITS.clientCode} characters or fewer.`
  if (!CLIENT_CODE_RE.test(v)) return "Only letters, digits, '-' and '_' are allowed (no spaces)."
  return null
}

export function validateName(value: string): string | null {
  const v = (value || '').trim()
  if (!v) return 'Name is required.'
  if (v.length > FIELD_LIMITS.name) return `Name must be ${FIELD_LIMITS.name} characters or fewer.`
  return null
}

export function validateEmail(value: string, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? 'Email is required.' : null
  if (v.length > FIELD_LIMITS.email) return `Email must be ${FIELD_LIMITS.email} characters or fewer.`
  if (!EMAIL_RE.test(v)) return 'Enter a valid email address (name@domain).'
  return null
}

export function validatePhone(value: string, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? 'Phone is required.' : null
  if (v.length > FIELD_LIMITS.phone) return `Phone must be ${FIELD_LIMITS.phone} characters or fewer.`
  if (!PHONE_RE.test(v)) return 'Enter a valid phone (digits, spaces, +, -, (), . only).'
  // Digits-only sanity: at least 7 real digits so an accidental "-()." doesn't sneak through.
  const digits = v.replace(/\D/g, '')
  if (digits.length < 7) return 'Phone needs at least 7 digits.'
  if (digits.length > 15) return 'Phone must not exceed 15 digits (E.164 max).'
  return null
}

export function validateCountry(value: string, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? 'Country is required.' : null
  if (!COUNTRY_RE.test(v)) return 'Country must be a 2-letter ISO code (e.g. US, GB).'
  return null
}

/** Country-aware zip check. Falls back to a permissive "alphanumeric ≥3" rule
 *  for unmodelled countries so we don't reject valid postcodes we can't spec. */
export function validateZip(zip: string, country: string, required = false): string | null {
  const z = (zip || '').trim()
  if (!z) return required ? 'Postal code is required.' : null
  if (z.length > FIELD_LIMITS.addr.zip) return `Postal code must be ${FIELD_LIMITS.addr.zip} characters or fewer.`
  const iso = (country || '').trim().toUpperCase()
  const re = ZIP_PATTERNS[iso]
  if (re) {
    if (!re.test(z)) return `Postal code doesn't match the ${iso} format.`
    return null
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9 -]{2,}$/.test(z)) {
    return 'Enter a valid postal code.'
  }
  return null
}

/** Bounded-length string check — used for line1, city, state, etc. */
export function validateLength(value: string, max: number, label: string, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? `${label} is required.` : null
  if (v.length > max) return `${label} must be ${max} characters or fewer.`
  return null
}

/** Rejects leading/trailing whitespace or all-whitespace values. Backend
 *  doesn't trim on save, so we surface it here to prevent invisible chars
 *  from silently persisting. */
export function warnWhitespace(value: string, label: string): string | null {
  if (!value) return null
  if (value !== value.trim()) return `${label} has leading or trailing whitespace — it'll be trimmed on save.`
  return null
}

export type AddressLike = {
  name?: string | null
  line1?: string | null
  line2?: string | null
  city?: string | null
  state?: string | null
  zip?: string | null
  country?: string | null
  phone?: string | null
}

/** Compact per-field errors for an address block. All fields required by
 *  default because a shippable address needs at minimum line1/city/state/zip/
 *  country. `contactName` is treated as required too — carrier labels demand it. */
export function validateAddress(
  addr: AddressLike | null | undefined,
  { required = true }: { required?: boolean } = {},
): Partial<Record<keyof AddressLike, string>> {
  const errors: Partial<Record<keyof AddressLike, string>> = {}
  const a = addr ?? {}
  if (required) {
    const nameErr = validateLength(a.name || '', FIELD_LIMITS.addr.name, 'Contact / company', true)
    if (nameErr) errors.name = nameErr
    const line1Err = validateLength(a.line1 || '', FIELD_LIMITS.addr.line1, 'Street address', true)
    if (line1Err) errors.line1 = line1Err
    const cityErr = validateLength(a.city || '', FIELD_LIMITS.addr.city, 'City', true)
    if (cityErr) errors.city = cityErr
    const stateErr = validateLength(a.state || '', FIELD_LIMITS.addr.state, 'State / region', true)
    if (stateErr) errors.state = stateErr
    const zipErr = validateZip(a.zip || '', a.country || '', true)
    if (zipErr) errors.zip = zipErr
    const countryErr = validateCountry(a.country || '', true)
    if (countryErr) errors.country = countryErr
  } else {
    // Non-required address (e.g. Return when "same as Ship From" is off but
    // fields have some content) — validate only what's typed.
    if (a.zip) {
      const zipErr = validateZip(a.zip, a.country || '', false)
      if (zipErr) errors.zip = zipErr
    }
    if (a.country) {
      const countryErr = validateCountry(a.country, false)
      if (countryErr) errors.country = countryErr
    }
  }
  // Optional line2 length is bounded either way.
  if (a.line2) {
    const line2Err = validateLength(a.line2, FIELD_LIMITS.addr.line2, 'Suite / unit', false)
    if (line2Err) errors.line2 = line2Err
  }
  if (a.phone) {
    const phoneErr = validatePhone(a.phone, false)
    if (phoneErr) errors.phone = phoneErr
  }
  return errors
}

/** Reject if the object has any error keys — helper for step-gating. */
export function hasErrors(errors: Record<string, unknown>): boolean {
  return Object.values(errors).some((v) => v != null && v !== '')
}

/**
 * Live duplicate-code check — the backend already rejects a POST with
 * CLIENT_CODE_TAKEN, but by then the operator has filled every step. Calling
 * this on blur surfaces the conflict at the identity step so the wizard
 * bails early.
 *
 * Returns `null` when the code is available or the check couldn't run
 * (network / auth failure — better to let the save attempt surface the real
 * error than block on a transient one), and an error string when the code
 * is definitively taken.
 */
export async function checkClientCodeAvailable(
  code: string,
  fetchClient: (code: string) => Promise<unknown>,
): Promise<string | null> {
  const v = (code || '').trim().toUpperCase()
  if (!v || validateClientCode(v) != null) return null // let field-shape errors surface first
  try {
    await fetchClient(v)
    // Success = the code resolves = someone already owns it.
    return `Client code ${v} is already registered.`
  } catch (err: unknown) {
    // ApiError carries a numeric status; 404 is the "not found" we want.
    const status = (err as { status?: number })?.status
    if (status === 404) return null
    // Non-404 error is inconclusive — don't block the operator over a
    // transient network hiccup; let the save call surface the real problem.
    return null
  }
}
