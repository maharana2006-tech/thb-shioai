/**
 * Turns a raw carrier rejection (the message persisted on a failed order — often
 * a FedEx/UPS error blob with codes + a JSON `errors[]` array) into:
 *   - a SHORT human summary for the orders table, and
 *   - a map of form-field path -> message, so the fix/edit form can show the
 *     error under the exact field that's wrong or missing.
 */

export type FieldErrorMap = Record<string, string>

/** Pull the human `"message":"..."` texts out of a carrier error blob. */
function extractMessages(raw: string): string[] {
  const out: string[] = []
  const re = /"message"\s*:\s*"((?:[^"\\]|\\.)*)"/g
  let m: RegExpExecArray | null
  while ((m = re.exec(raw)) !== null) {
    const t = m[1].replace(/\\"/g, '"').trim()
    if (t) out.push(t)
  }
  return out
}

/** A short, readable one-liner for the orders table. */
export function summarizeCarrierError(raw?: string | null): string {
  if (!raw) return 'Label failed'
  const msgs = extractMessages(raw)
  if (msgs.length) return msgs.join(' · ')
  // No JSON messages — strip our wrapper prefix and any HTTP/transaction noise.
  return raw
    .replace(/^The carrier rejected[^:]*:\s*/i, '')
    .replace(/\b[A-Z]+ createShipment HTTP \d+:?\s*/i, '')
    .replace(/\{.*\}$/s, '')
    .trim()
    .slice(0, 160) || 'Label failed'
}

/**
 * Map a carrier error to the form fields it implicates. Paths match the fix
 * form: recipient.* / sender.* / weight / declaredValue / package. `hasItemError`
 * flags customs/commodity problems so the items table can show a banner.
 */
export function mapCarrierErrorToFields(raw?: string | null): { fields: FieldErrorMap; hasItemError: boolean } {
  const fields: FieldErrorMap = {}
  if (!raw) return { fields, hasItemError: false }
  const msg = raw.toUpperCase()
  const set = (path: string, m: string) => { if (!fields[path]) fields[path] = m }
  // Shipper/origin problems attach to the sender; everything else to recipient.
  const party = /SHIPPER|ORIGIN\b/.test(msg) ? 'sender' : 'recipient'

  if (/COUNTRY.*(NOTSERVED|NOT\.?SERVED)|NOT SERVED/.test(msg))
    set(`${party}.countryCode`, 'This country isn’t served — choose a different destination country.')
  if (/POSTAL|ZIP/.test(msg))
    set(`${party}.postalCode`, 'Postal / ZIP code is invalid or missing for this country.')
  if (/STATEORPROVINCE|STATE\.?CODE|PROVINCE/.test(msg))
    set(`${party}.state`, 'State / province is invalid or required.')
  if (/\bCITY\b/.test(msg))
    set(`${party}.city`, 'City is invalid or missing.')
  if (/PHONE/.test(msg))
    set(`${party}.phone`, 'Phone number is invalid or missing.')
  if (/STREET|ADDRESS.?LINE|ADDRESS.*(REQUIRED|INVALID|NOT)/.test(msg))
    set(`${party}.addressLine1`, 'Street address is invalid or missing.')
  if (/RECIPIENT.*(NAME|CONTACT).*REQUIRED|CONTACT.?NAME/.test(msg))
    set(`${party}.name`, 'Contact name is required.')
  if (/PACKAGINGTYPE|PACKAGING/.test(msg))
    set('package', 'This packaging isn’t valid for the selected service — change the box or the service.')
  if (/\bWEIGHT\b/.test(msg))
    set('weight', 'Weight is invalid or missing.')
  if (/CUSTOMSVALUE|CARRIAGEVALUE|DECLARED.?VALUE|TOTALDECLARED/.test(msg))
    set('declaredValue', 'Declared / customs value is missing or inconsistent with the item values.')

  const hasItemError = /COMMODIT|UNITPRICE|UNIT\.?PRICE|HARMONIZED|\bHS\.?CODE\b|LINEITEM|CUSTOMSVALUE|CARRIAGEVALUE/.test(msg)
  return { fields, hasItemError }
}
