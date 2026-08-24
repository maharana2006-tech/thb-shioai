/**
 * Validation for the "Add / edit carrier account" drawer (CarrierConnections).
 *
 * Carrier-account onboarding has domain-specific rules that a generic form
 * validator misses:
 *  - the account number's format is carrier-specific (UPS shipper # ≠ FedEx #);
 *  - credential fields are opaque tokens where the common failure mode is a
 *    pasted key with a stray space/newline that silently breaks auth — so we
 *    trim, reject embedded whitespace, and bound the length;
 *  - on EDIT the account number is locked and credentials are only required
 *    when the operator explicitly rotates them.
 *
 * Returns a { field: message } map — empty means valid. Kept as a plain
 * function (rather than a Yup schema) because the rules branch on carrier +
 * edit/rotate state, which reads far clearer imperatively.
 */

const NO_ANGLE = /^[^<>]*$/
const NO_ANGLE_MSG = 'Cannot contain < or >'

/** Canonical Stamps.com IntegrationID shape: 8-4-4-4-12 hex with hyphens. */
const CANONICAL_GUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
/** 32-hex-no-hyphens variant some Stamps.com SDK samples emit. */
const GUID_NO_HYPHENS = /^[0-9a-fA-F]{32}$/
/** {@code {01234567-…}} form the Windows dev-portal copy button emits. */
const BRACED_GUID = /^\{(.*)\}$/
/** IETF-style prefix; case-insensitive per RFC 4122. */
const URN_UUID_PREFIX = /^urn:uuid:/i

/**
 * True when {@code raw} can be normalised into a canonical Stamps.com
 * IntegrationID. Kept in lockstep with StampsConnector.normaliseIntegrationId
 * on the backend so the FE and BE agree on what's accepted — variants:
 *  - canonical `8-4-4-4-12` — pass-through
 *  - braced `{...}` — strip braces
 *  - URN `urn:uuid:...` — strip prefix
 *  - 32-hex-no-hyphens — insert hyphens
 */
function isValidStampsIntegrationId(raw: string): boolean {
  let s = raw.trim()
  if (URN_UUID_PREFIX.test(s)) s = s.replace(URN_UUID_PREFIX, '').trim()
  const braced = BRACED_GUID.exec(s)
  if (braced) s = braced[1].trim()
  if (GUID_NO_HYPHENS.test(s)) {
    s = `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`
  }
  return CANONICAL_GUID.test(s)
}

/** Per-carrier account-number formats; anything else uses GENERIC. */
const ACCOUNT_RULES: Record<string, { re: RegExp; msg: string }> = {
  UPS: { re: /^[A-Za-z0-9]{6,10}$/, msg: 'UPS shipper number is 6–10 letters or digits' },
  FEDEX: { re: /^\d{9}$/, msg: 'FedEx account number is exactly 9 digits' },
  DHL: { re: /^\d{9,12}$/, msg: 'DHL Express account number is 9–12 digits' },
  USPS: { re: /^[A-Za-z0-9._@-]{3,50}$/, msg: 'Enter a valid Stamps.com username' },
}
const GENERIC_ACCOUNT = { re: /^[A-Za-z0-9._@-]{3,25}$/, msg: 'Use 3–25 letters, digits, or . _ @ -' }

export interface CarrierAccountValues {
  carrierCode: string
  accountType: 'platform' | 'client'
  accountNumber: string
  accountName: string
  clientId: string
  clientSecret: string
  customerNo: string
  environment: string
}

export interface CarrierAccountContext {
  /** Editing an existing account — the account number is locked. */
  isEdit: boolean
  /** On edit, the operator chose to replace the stored credentials. */
  rotating: boolean
  /** Carrier-specific field names, for messages that match the carrier's portal. */
  labels: { accountNumberLabel: string; idLabel: string; secretLabel: string }
}

export type CarrierAccountErrors = Partial<Record<keyof CarrierAccountValues, string>>

export function validateCarrierAccount(
  v: CarrierAccountValues,
  ctx: CarrierAccountContext,
): CarrierAccountErrors {
  const e: CarrierAccountErrors = {}
  const carrier = (v.carrierCode || '').toUpperCase()
  const isUsps = carrier === 'USPS'
  const credsRequired = !ctx.isEdit || ctx.rotating
  const { accountNumberLabel, idLabel, secretLabel } = ctx.labels

  // Account name / nickname — optional.
  const name = v.accountName.trim()
  if (name.length > 40) e.accountName = 'Max 40 characters'
  else if (name && !NO_ANGLE.test(name)) e.accountName = NO_ANGLE_MSG

  // Account number / username — required + carrier format. Locked (skip) on edit.
  if (!ctx.isEdit) {
    const acct = v.accountNumber.trim()
    if (!acct) {
      e.accountNumber = `${accountNumberLabel} is required`
    } else {
      const rule = ACCOUNT_RULES[carrier] ?? GENERIC_ACCOUNT
      if (!rule.re.test(acct)) e.accountNumber = rule.msg
    }
  }

  // Client selection for a client-scoped account.
  if (v.accountType === 'client' && !v.customerNo.trim()) {
    e.customerNo = 'Choose a client for a client account'
  }

  // Credentials — the high-value checks. Opaque tokens: trim, no embedded
  // whitespace, length sanity. (USPS secret is a real password, so spaces ok.)
  if (credsRequired) {
    const idT = v.clientId.trim()
    if (!idT) e.clientId = `${idLabel} is required`
    else if (idT.length < 6) e.clientId = `That ${idLabel} looks too short — double-check it`
    else if (idT.length > 120) e.clientId = 'Too long — did you paste extra text?'
    else if (/\s/.test(idT)) e.clientId = 'Remove spaces — paste the key exactly as issued'
    else if (!NO_ANGLE.test(idT)) e.clientId = NO_ANGLE_MSG
    // Stamps.com SWSIM requires the IntegrationID to be a GUID. Reject inline
    // BEFORE the operator hits Save/Verify (used to fail server-side with a
    // "must be a GUID" message that showed up only after the round trip).
    // Accepts the same shapes the backend normaliser does: canonical
    // 8-4-4-4-12, braced {…}, urn:uuid: prefix, and 32-hex-no-hyphens.
    else if (isUsps && !isValidStampsIntegrationId(idT)) {
      e.clientId = `${idLabel} must be a GUID (e.g. 01234567-89ab-cdef-0123-456789abcdef)`
    }

    const secT = v.clientSecret.trim()
    if (!secT) e.clientSecret = `${secretLabel} is required`
    else if (secT.length < 6) e.clientSecret = `That ${secretLabel} looks too short — double-check it`
    else if (secT.length > 200) e.clientSecret = 'Too long — did you paste extra text?'
    else if (!isUsps && /\s/.test(secT)) e.clientSecret = 'Remove spaces — paste the secret exactly as issued'
    else if (!NO_ANGLE.test(secT)) e.clientSecret = NO_ANGLE_MSG
  }

  return e
}
