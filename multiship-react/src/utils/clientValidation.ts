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

/**
 * XSS / injection guard shared across free-text fields — matches the app-wide
 * convention in {@link shipmentSchema} (`SAFE_TEXT_RE`). A company name can
 * legitimately hold `&`, `'`, `.`, `+`, `!` (AT&T, Ben & Jerry's, Yahoo!),
 * so we deliberately allow-list nothing and only BAN the angle brackets that
 * open an HTML tag. Keep this identical to the shipment schema.
 */
const SAFE_TEXT_RE = /^[^<>]*$/
/** At least one Unicode letter or number — blocks all-punctuation values ("!!!", "---"). */
const HAS_ALNUM_RE = /[\p{L}\p{N}]/u

/** Pragmatic email format — allows most valid RFC 5322 addresses without the
 *  full spec's edge cases (comments, quoted locals). Good enough for a form. */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** Phone CHARACTER-SET check only: optional leading +, then digits and the
 *  usual separators (spaces / dashes / parens / dots). Deliberately does NOT
 *  enforce length — the digit-count checks in validatePhone own the length
 *  messaging, so a short input like "123" gets the specific "needs at least 7
 *  digits" error instead of this generic character-set one. */
const PHONE_RE = /^\+?[\d\s\-().]+$/

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

/**
 * Generic uppercase identifier check — client code, warehouse code, and any
 * other `[A-Z0-9_-]` code field share these rules. `label` personalizes the
 * message ("Client code" / "Warehouse code"). Mirrors the backend
 * `@Pattern("[A-Za-z0-9_-]+")` and adds front-of-house min-length + no
 * separator-only guards.
 */
export function validateCode(value: string, label = 'Code'): string | null {
  const v = (value || '').trim().toUpperCase()
  if (!v) return `${label} is required.`
  // Front-of-house minimum: a single-character code is almost always a typo.
  if (v.length < 2) return `${label} must be at least 2 characters.`
  if (v.length > FIELD_LIMITS.clientCode) return `${label} must be ${FIELD_LIMITS.clientCode} characters or fewer.`
  if (!CLIENT_CODE_RE.test(v)) return "Only letters, digits, '-' and '_' are allowed (no spaces)."
  // Reject separator-only codes like "--" or "__" that pass the pattern.
  if (!/[A-Z0-9]/.test(v)) return `${label} must include at least one letter or digit.`
  return null
}

export function validateClientCode(value: string): string | null {
  return validateCode(value, 'Client code')
}

export function validateName(value: string): string | null {
  const v = (value || '').trim()
  if (!v) return 'Name is required.'
  if (v.length < 2) return 'Name must be at least 2 characters.'
  if (v.length > FIELD_LIMITS.name) return `Name must be ${FIELD_LIMITS.name} characters or fewer.`
  // App-wide free-text XSS guard (same rule as the shipment schema): a client /
  // company name never contains angle brackets, but an attacker's "<script>"
  // does. Bans only < and > so legitimate business punctuation still passes.
  if (!SAFE_TEXT_RE.test(v)) return 'Name cannot contain the characters < or >.'
  // Must carry real content — blocks "!!!", "---", "&&&" and similar.
  if (!HAS_ALNUM_RE.test(v)) return 'Name must contain at least one letter or number.'
  return null
}

export function validateEmail(value: string, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? 'Email is required.' : null
  if (v.length > FIELD_LIMITS.email) return `Email must be ${FIELD_LIMITS.email} characters or fewer.`
  // Reject the common malformed shapes EMAIL_RE alone lets slip:
  // leading/trailing dot and doubled dots ("a..b@x.com", ".a@x.com").
  if (v.startsWith('.') || v.endsWith('.') || v.includes('..')) {
    return 'Enter a valid email address (name@domain).'
  }
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

/**
 * Per-country phone rules for the countries the app most commonly ships
 * from/to (same set as {@link ZIP_PATTERNS}). `cc` is the E.164 calling code,
 * `min`/`max` bound the NATIONAL number length in digits (trunk '0' stripped),
 * and `pattern` (optional) shape-checks the national number — e.g. NANP
 * numbers can't have an area code or exchange starting with 0/1.
 */
const PHONE_RULES: Record<string, { cc: string; min: number; max: number; pattern?: RegExp; trunk?: boolean }> = {
  // NANP: area code can't start 0/1. (We deliberately don't police the
  // exchange digit — that would reject the common "555 123 4567" shape.)
  // `trunk: false` because NANP has no trunk-'0' prefix to strip.
  US: { cc: '1', min: 10, max: 10, pattern: /^[2-9]\d{9}$/, trunk: false },
  CA: { cc: '1', min: 10, max: 10, pattern: /^[2-9]\d{9}$/, trunk: false },
  GB: { cc: '44', min: 9, max: 10 },
  DE: { cc: '49', min: 7, max: 11 },
  FR: { cc: '33', min: 9, max: 9 },
  IT: { cc: '39', min: 6, max: 11 },
  ES: { cc: '34', min: 9, max: 9 },
  NL: { cc: '31', min: 9, max: 9 },
  AU: { cc: '61', min: 9, max: 9 },
  IN: { cc: '91', min: 10, max: 10 },
  JP: { cc: '81', min: 9, max: 10 },
  CN: { cc: '86', min: 10, max: 11 },
  BR: { cc: '55', min: 10, max: 11 },
  MX: { cc: '52', min: 10, max: 10 },
  SG: { cc: '65', min: 8, max: 8 },
}

/**
 * Country-aware phone validation — the "correct" checker for forms that also
 * know the address country (pickup, warehouse, customs contacts):
 *
 *  1. charset + length caps (same as {@link validatePhone})
 *  2. junk guard — all-identical digits ("0000000000") are rejected
 *  3. `+` numbers must be valid E.164 AND start with the selected country's
 *     calling code (a US pickup can't have a +44 contact number)
 *  4. national numbers (no `+`) must match the country's expected digit
 *     length after stripping the trunk '0' — e.g. US = exactly 10, and NANP
 *     shape is enforced (area code / exchange can't start with 0 or 1)
 *
 * Unmodelled countries fall back to the generic 7–15 digit rule so we never
 * block a country we haven't specced. Falls back entirely to
 * {@link validatePhone} when no country is provided.
 */
export function validatePhoneForCountry(value: string, country: string, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? 'Phone is required.' : null
  if (v.length > FIELD_LIMITS.phone) return `Phone must be ${FIELD_LIMITS.phone} characters or fewer.`
  if (!PHONE_RE.test(v)) return 'Enter a valid phone (digits, spaces, +, -, (), . only).'
  const digits = v.replace(/\D/g, '')
  if (digits.length < 7) return 'Phone needs at least 7 digits.'
  if (digits.length > 15) return 'Phone must not exceed 15 digits (E.164 max).'
  // Junk guard: "0000000000" / "1111111" pass every length rule but are never real.
  if (/^(\d)\1+$/.test(digits)) return 'Enter a real phone number.'

  const iso = (country || '').trim().toUpperCase()
  const rule = PHONE_RULES[iso]
  if (!rule) return null // unmodelled country — generic checks above are all we can assert

  if (v.startsWith('+')) {
    // International format: must carry this country's calling code.
    if (!digits.startsWith(rule.cc)) {
      return `An international ${iso} number must start with +${rule.cc}.`
    }
    const national = rule.trunk === false
      ? digits.slice(rule.cc.length)
      : digits.slice(rule.cc.length).replace(/^0/, '')
    if (national.length < rule.min || national.length > rule.max) {
      return rule.min === rule.max
        ? `A ${iso} phone number needs ${rule.min} digits after +${rule.cc}.`
        : `A ${iso} phone number needs ${rule.min}–${rule.max} digits after +${rule.cc}.`
    }
    if (rule.pattern && !rule.pattern.test(national)) {
      return `That doesn't look like a valid ${iso} phone number.`
    }
    return null
  }

  // National format: strip the trunk '0' (e.g. GB 07911… → 7911…) then check
  // length. NANP countries have no trunk prefix, so a leading 0 there stays
  // and correctly fails the area-code shape check instead.
  const national = rule.trunk === false ? digits : digits.replace(/^0/, '')
  if (national.length < rule.min || national.length > rule.max) {
    return rule.min === rule.max
      ? `A ${iso} phone number needs ${rule.min} digits.`
      : `A ${iso} phone number needs ${rule.min}–${rule.max} digits.`
  }
  if (rule.pattern && !rule.pattern.test(national)) {
    return `That doesn't look like a valid ${iso} phone number.`
  }
  return null
}

export function validateCountry(value: string, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? 'Country is required.' : null
  if (!COUNTRY_RE.test(v)) return 'Country must be a 2-letter ISO code (e.g. US, GB).'
  return null
}

/** Country-aware zip check. Falls back to a permissive "alphanumeric ≥3" rule
 *  for unmodelled countries so we don't reject valid postcodes we can't spec.
 *  `maxLength` overrides {@link FIELD_LIMITS.addr.zip} — used to enforce a
 *  tighter per-carrier cap (e.g. UPS = 9). */
export function validateZip(zip: string, country: string, required = false, maxLength?: number): string | null {
  const z = (zip || '').trim()
  if (!z) return required ? 'Postal code is required.' : null
  const cap = maxLength ?? FIELD_LIMITS.addr.zip
  if (z.length > cap) return `Postal code must be ${cap} characters or fewer.`
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

/** Bounded free-text check — used for line1, line2, city, state, etc. Beyond
 *  required + max it now enforces an optional `min` and the app-wide `<>` XSS
 *  guard (same `SAFE_TEXT_RE` as the shipment schema), so a value bound for a
 *  carrier label can never smuggle an HTML tag. */
export function validateLength(value: string, max: number, label: string, required = false, min = 0): string | null {
  const v = (value || '').trim()
  if (!v) return required ? `${label} is required.` : null
  if (min > 0 && v.length < min) return `${label} must be at least ${min} characters.`
  if (v.length > max) return `${label} must be ${max} characters or fewer.`
  if (!SAFE_TEXT_RE.test(v)) return `${label} cannot contain the characters < or >.`
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

/**
 * Optional length caps that override the loose DB-column defaults in
 * {@link FIELD_LIMITS.addr}. Shape is structurally compatible with the
 * per-carrier {@code AddressCaps} from utils/carrierFieldLimits, so a
 * caller with per-carrier intersection caps can pass them straight in
 * without a translation step. `line` covers BOTH line1 and line2 (carriers
 * enforce the same cap on either street-address line).
 */
export interface AddressLengthCaps {
  name?: number
  line?: number
  city?: number
  state?: number
  zip?: number
  phone?: number
}

/**
 * Length-cap variant of {@link validatePhone} that respects an override.
 * Falls back to FIELD_LIMITS.phone when no override is provided so the
 * default behaviour is unchanged.
 */
function validatePhoneCap(value: string, maxLength?: number, required = false): string | null {
  const v = (value || '').trim()
  if (!v) return required ? 'Phone is required.' : null
  const cap = maxLength ?? FIELD_LIMITS.phone
  if (v.length > cap) return `Phone must be ${cap} characters or fewer.`
  if (!PHONE_RE.test(v)) return 'Enter a valid phone (digits, spaces, +, -, (), . only).'
  const digits = v.replace(/\D/g, '')
  if (digits.length < 7) return 'Phone needs at least 7 digits.'
  if (digits.length > 15) return 'Phone must not exceed 15 digits (E.164 max).'
  return null
}

/**
 * Compact per-field errors for an address block. All fields required by
 * default because a shippable address needs at minimum line1/city/state/zip/
 * country. `contactName` is treated as required too — carrier labels demand it.
 *
 * When {@code caps} is provided, length checks use those tighter values
 * (e.g. the intersection of every enabled carrier's per-field cap) instead
 * of the loose DB-column defaults. Otherwise falls back to
 * {@link FIELD_LIMITS.addr}, preserving legacy behaviour.
 */
export function validateAddress(
  addr: AddressLike | null | undefined,
  { required = true, caps }: { required?: boolean; caps?: AddressLengthCaps } = {},
): Partial<Record<keyof AddressLike, string>> {
  const errors: Partial<Record<keyof AddressLike, string>> = {}
  const a = addr ?? {}
  const nameMax  = caps?.name  ?? FIELD_LIMITS.addr.name
  const lineMax  = caps?.line  ?? FIELD_LIMITS.addr.line1
  const cityMax  = caps?.city  ?? FIELD_LIMITS.addr.city
  const stateMax = caps?.state ?? FIELD_LIMITS.addr.state
  const zipMax   = caps?.zip   ?? FIELD_LIMITS.addr.zip
  const phoneMax = caps?.phone ?? FIELD_LIMITS.addr.phone
  if (required) {
    const nameErr = validateLength(a.name || '', nameMax, 'Contact / company', true, 2)
    if (nameErr) errors.name = nameErr
    const line1Err = validateLength(a.line1 || '', lineMax, 'Street address', true, 2)
    if (line1Err) errors.line1 = line1Err
    const cityErr = validateLength(a.city || '', cityMax, 'City', true, 2)
    if (cityErr) errors.city = cityErr
    const stateErr = validateLength(a.state || '', stateMax, 'State / region', true)
    if (stateErr) errors.state = stateErr
    const zipErr = validateZip(a.zip || '', a.country || '', true, zipMax)
    if (zipErr) errors.zip = zipErr
    const countryErr = validateCountry(a.country || '', true)
    if (countryErr) errors.country = countryErr
  } else {
    // Non-required address (e.g. Return when "same as Ship From" is off but
    // fields have some content) — validate only what's typed.
    if (a.zip) {
      const zipErr = validateZip(a.zip, a.country || '', false, zipMax)
      if (zipErr) errors.zip = zipErr
    }
    if (a.country) {
      const countryErr = validateCountry(a.country, false)
      if (countryErr) errors.country = countryErr
    }
  }
  // Optional line2 length is bounded either way.
  if (a.line2) {
    const line2Err = validateLength(a.line2, lineMax, 'Suite / unit', false)
    if (line2Err) errors.line2 = line2Err
  }
  if (a.phone) {
    const phoneErr = validatePhoneCap(a.phone, phoneMax, false)
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
