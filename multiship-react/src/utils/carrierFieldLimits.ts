/**
 * Per-carrier field constraints for the Add Client wizard (and eventually
 * the manual shipment / editor surfaces). Numbers here are drawn from each
 * carrier's public API contract + this repo's carrier connectors:
 *   UPS   — UPS Shipping REST v1 (JSON)         : UpsConnector
 *   FEDEX — FedEx Ship API v1 (JSON)            : FedExConnector
 *   USPS  — Stamps.com SWSIM v135 (SOAP)        : StampsConnector
 *   DHL   — DHL Express MyDHL API v2 (JSON)     : DhlConnector
 *
 * When a client is enabled for multiple carriers, the wizard applies the
 * INTERSECTION of the caps below — the value must satisfy every carrier
 * on the client's allowlist, otherwise a label bought on the strictest
 * carrier will get rejected at generation time.
 */

export type CarrierCode = 'UPS' | 'FEDEX' | 'USPS' | 'DHL'

/**
 * Cap + pattern for the carrier account number itself (the identifier
 * the carrier issued the client). Very carrier-specific — UPS is
 * exactly 6 digits, FedEx up to 12 alphanumerics, etc. Applied
 * dynamically to the account-number input as the carrier dropdown
 * changes.
 */
export interface CarrierAccountRule {
  /** Max length of the account number field. */
  maxLength: number
  /** UI placeholder — describes format so the operator knows what to type. */
  placeholder: string
  /**
   * HTML5 pattern the input should match (attempts to submit will show a
   * browser tooltip). Kept permissive on carriers whose docs allow mixed
   * chars; strict on UPS which is documented digits-only.
   */
  pattern: string
  /** Short line below the input describing the rule. */
  helper: string
}

export const CARRIER_ACCOUNT_RULES: Record<CarrierCode, CarrierAccountRule> = {
  UPS: {
    maxLength: 6,
    placeholder: '6-digit UPS shipper number',
    pattern: '[A-Za-z0-9]{6}',
    helper: 'UPS shipper numbers are exactly 6 alphanumerics.',
  },
  FEDEX: {
    maxLength: 12,
    placeholder: 'Up to 12 digits (FedEx account #)',
    pattern: '[0-9A-Za-z]{1,12}',
    helper: 'FedEx account numbers are up to 12 digits.',
  },
  DHL: {
    maxLength: 9,
    placeholder: 'Up to 9 digits (DHL account #)',
    pattern: '[0-9A-Za-z]{1,9}',
    helper: 'DHL Express account numbers are up to 9 digits.',
  },
  USPS: {
    maxLength: 40,
    placeholder: 'Stamps.com Username (up to 40 chars)',
    pattern: '.{1,40}',
    helper: 'For USPS via Stamps.com, this is your Stamps.com Username (the account you sign in with).',
  },
}

/**
 * Per-carrier maximum lengths on the fields the shipper/recipient
 * address blocks map to at the carrier. These are the caps the carrier's
 * OWN API rejects past — nothing in our connector truncates.
 */
export interface AddressCaps {
  /** Contact / company name (UPS `ShipperName`, FedEx `personName/companyName`, etc). */
  name: number
  /** Each of address line 1 / 2 / 3. */
  line: number
  city: number
  /** State / province code. */
  state: number
  /** Postal code — USPS allows a dash form (5+4). */
  zip: number
  /** Phone number, digits including any leading dial code. */
  phone: number
}

export const CARRIER_ADDRESS_CAPS: Record<CarrierCode, AddressCaps> = {
  UPS: { name: 35, line: 35, city: 30, state: 5, zip: 9, phone: 15 },
  FEDEX: { name: 70, line: 35, city: 35, state: 5, zip: 16, phone: 15 },
  USPS: { name: 40, line: 40, city: 40, state: 2, zip: 10, phone: 30 },
  DHL: { name: 60, line: 45, city: 45, state: 35, zip: 12, phone: 25 },
}

/** Loose caps used when a client has no enabled carriers yet — matches DB column caps
 *  so the wizard doesn't reject data the schema would otherwise accept. */
const UNCONSTRAINED: AddressCaps = {
  name: 255, line: 255, city: 100, state: 50, zip: 20, phone: 50,
}

/**
 * Intersect the per-carrier caps: for each field, take the smallest cap
 * across every enabled carrier. Guarantees any value the operator types
 * will satisfy every carrier the client is enabled for.
 *
 * Empty set → returns the unconstrained (DB-column-length) caps so the
 * wizard doesn't accidentally reject values the schema stores fine.
 */
export function intersectionAddressCaps(carriers: Iterable<string>): AddressCaps {
  const codes = normaliseCarrierCodes(carriers)
  if (codes.length === 0) return UNCONSTRAINED
  const first = CARRIER_ADDRESS_CAPS[codes[0]]
  const out: AddressCaps = { ...first }
  for (let i = 1; i < codes.length; i++) {
    const c = CARRIER_ADDRESS_CAPS[codes[i]]
    out.name = Math.min(out.name, c.name)
    out.line = Math.min(out.line, c.line)
    out.city = Math.min(out.city, c.city)
    out.state = Math.min(out.state, c.state)
    out.zip = Math.min(out.zip, c.zip)
    out.phone = Math.min(out.phone, c.phone)
  }
  return out
}

/** Human-readable summary of a caps set — for helper text below a field. */
export function summariseCaps(caps: AddressCaps): string {
  return `name ≤ ${caps.name} · line ≤ ${caps.line} · city ≤ ${caps.city} · state ≤ ${caps.state} · zip ≤ ${caps.zip} · phone ≤ ${caps.phone}`
}

/** Which carriers set the currently-binding limit for a given field? */
export function bindingCarriers(caps: AddressCaps, enabled: Iterable<string>, field: keyof AddressCaps): CarrierCode[] {
  const codes = normaliseCarrierCodes(enabled)
  return codes.filter((c) => CARRIER_ADDRESS_CAPS[c][field] === caps[field])
}

// ===== data-type validation =====

/**
 * Postal-code patterns for the countries we ship to most. Anchored to
 * whole-input matches. HK entry is `null` because Hong Kong has no
 * postal codes — the field should be left blank there. Fallback for
 * countries not in the map: any non-empty string up to the carrier cap.
 */
export const ZIP_PATTERNS: Record<string, RegExp | null> = {
  US: /^\d{5}(-\d{4})?$/,
  CA: /^[A-Z]\d[A-Z] ?\d[A-Z]\d$/,
  GB: /^[A-Z]{1,2}\d[A-Z\d]? ?\d[A-Z]{2}$/,
  DE: /^\d{5}$/,
  FR: /^\d{5}$/,
  ES: /^\d{5}$/,
  IT: /^\d{5}$/,
  NL: /^\d{4} ?[A-Z]{2}$/,
  IN: /^\d{6}$/,
  JP: /^\d{3}-?\d{4}$/,
  CN: /^\d{6}$/,
  AU: /^\d{4}$/,
  BR: /^\d{5}-?\d{3}$/,
  MX: /^\d{5}$/,
  SG: /^\d{6}$/,
  HK: null,
}

/** Validate a ZIP against a country. Blank is allowed (fields are
 *  individually optional at wizard time). Countries not in the map
 *  accept anything. */
export function validateZip(zip: string, country: string): { ok: boolean; message?: string } {
  const trimmed = zip.trim()
  if (!trimmed) return { ok: true }
  const key = country.trim().toUpperCase()
  const pat = ZIP_PATTERNS[key]
  if (pat === undefined) return { ok: true } // unknown country → no validation
  if (pat === null) {
    return trimmed.length === 0
      ? { ok: true }
      : { ok: false, message: `${key} does not use postal codes — leave this blank.` }
  }
  return pat.test(trimmed.toUpperCase())
    ? { ok: true }
    : { ok: false, message: `Not a valid ${key} postal code (${describeZip(key)}).` }
}

function describeZip(country: string): string {
  switch (country) {
    case 'US': return '5 digits, optionally +4 (e.g. 10001 or 10001-1234)'
    case 'CA': return 'A1A 1A1'
    case 'GB': return 'e.g. SW1A 1AA'
    case 'NL': return '1234 AB'
    case 'IN': return '6 digits'
    case 'JP': return '123-4567'
    case 'AU': return '4 digits'
    default: return 'check the carrier docs for this country'
  }
}

/**
 * State/province rule per country. US + CA carriers reject anything
 * other than the two-letter code; other countries accept longer
 * province names free-form.
 */
export function stateRule(country: string): { maxLength: number; pattern?: string; upper: boolean; hint: string } {
  const key = country.trim().toUpperCase()
  if (key === 'US' || key === 'CA') {
    return {
      maxLength: 2,
      pattern: '[A-Z]{2}',
      upper: true,
      hint: `${key} state/province must be the 2-letter code (e.g. NY, CA).`,
    }
  }
  return { maxLength: 35, upper: false, hint: '' }
}

/** RFC-ish email format check — mirrors what most carriers accept. */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
export function validateEmail(email: string): { ok: boolean; message?: string } {
  const trimmed = email.trim()
  if (!trimmed) return { ok: true }
  return EMAIL_RE.test(trimmed)
    ? { ok: true }
    : { ok: false, message: 'Enter a valid email address.' }
}

/** Strip control chars + XSS-adjacent bytes from address / name inputs
 *  as the user types. Non-blocking — the sanitizer just quietly drops
 *  chars carriers never accept. */
export function sanitizeText(v: string): string {
  // eslint-disable-next-line no-control-regex
  return v.replace(/[\x00-\x1f\x7f<>\\`|]/g, '')
}

/** Strict alnum + `_` + `-` filter for code fields (client code,
 *  warehouse code). Applied on keystroke so patterns are enforced in
 *  real time — HTML5 pattern only fires on submit. */
export function filterCode(v: string): string {
  return v.toUpperCase().replace(/[^A-Z0-9_-]/g, '')
}

/** Digits + optional dial `+` + spaces + dashes + parens. Rejects
 *  letters and everything else. */
export function filterPhone(v: string): string {
  return v.replace(/[^\d+\s()-]/g, '')
}

// ===== internal helpers =====

function normaliseCarrierCodes(carriers: Iterable<string>): CarrierCode[] {
  const out: CarrierCode[] = []
  for (const raw of carriers) {
    const upper = raw.toUpperCase()
    if (upper === 'UPS' || upper === 'FEDEX' || upper === 'USPS' || upper === 'DHL') {
      if (!out.includes(upper)) out.push(upper)
    }
  }
  return out
}
