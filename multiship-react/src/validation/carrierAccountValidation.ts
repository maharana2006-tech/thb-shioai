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

    const secT = v.clientSecret.trim()
    if (!secT) e.clientSecret = `${secretLabel} is required`
    else if (secT.length < 6) e.clientSecret = `That ${secretLabel} looks too short — double-check it`
    else if (secT.length > 200) e.clientSecret = 'Too long — did you paste extra text?'
    else if (!isUsps && /\s/.test(secT)) e.clientSecret = 'Remove spaces — paste the secret exactly as issued'
    else if (!NO_ANGLE.test(secT)) e.clientSecret = NO_ANGLE_MSG
  }

  return e
}
