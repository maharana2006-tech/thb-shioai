/**
 * Turns a raw carrier rejection (the message persisted on a failed order — often
 * a FedEx/UPS error blob with codes + a JSON `errors[]` array) into:
 *   - a SHORT human summary for the orders table, and
 *   - a map of form-field path -> message, so the fix/edit form can show the
 *     error under the exact field that's wrong or missing.
 */

export type FieldErrorMap = Record<string, string>

/** Pull the human `"message":"..."` / `"error_message":"..."` texts out of
 *  a carrier error blob. FedEx/UPS/DHL use `"message"`; Stamps.com SERA
 *  uses `"error_message"` — accept both so the SERA 400 shape doesn't
 *  fall through to the empty-stripper branch. */
function extractMessages(raw: string): string[] {
  const out: string[] = []
  const re = /"(?:error_)?message"\s*:\s*"((?:[^"\\]|\\.)*)"/g
  let m: RegExpExecArray | null
  while ((m = re.exec(raw)) !== null) {
    const t = m[1].replace(/\\"/g, '"').trim()
    if (t) out.push(t)
  }
  return out
}

/** Carriers sometimes omit the space after a sentence-ending period
 *  ("exist.Please update…") — normalize for display. Letters only, so
 *  decimals ("5007.20") and codes ("DESTINATION.COUNTRY") stay intact. */
const fixSentenceSpacing = (s: string) => s.replace(/([a-z])\.([A-Z])/g, '$1. $2')

/**
 * Translate Stamps.com SERA's cryptic error strings + numeric codes
 * into operator-friendly sentences. Returns {@code null} when nothing
 * matches so the caller falls back to the raw text.
 *
 * <p>Rules in priority order — first match wins. Keep the list short
 * and extend ONLY when a new error surfaces in production; a stale
 * mapping that drifts from reality is worse than the raw message.
 */
function humanizeStampsError(raw: string): string | null {
  const r = raw.toLowerCase()
  // Money / account state
  if (r.includes('insufficient account balance') || r.includes('5636353')) {
    return 'USPS postage balance is too low for this label. Add funds in Settings → Carriers (Stamps account) and retry.'
  }
  if (r.includes('payment server error') || r.includes('5636370')) {
    return 'USPS couldn’t charge your postage account. Verify the payment method on your Stamps.com portal, then retry.'
  }
  // Field validation (carrier error 4522242 family)
  if (r.includes('contents_description') && r.includes('invalid')) {
    return 'USPS rejected the customs contents description. Use a short generic summary (e.g. "bicycle parts", "clothing") instead of a per-item line.'
  }
  if (r.includes('certificate_number') && r.includes('invalid')) {
    return 'USPS rejected the export certificate. Check the ITN / FTR exemption on the shipment’s customs block.'
  }
  if (r.includes('service_type') && r.includes('invalid')) {
    return 'USPS doesn’t offer this shipping service on the lane. Pick a different service (e.g. Priority Mail International for heavy intl parcels).'
  }
  if (r.includes('packaging_type') && r.includes('invalid')) {
    return 'USPS rejected the packaging type for this service. Flat-rate boxes work with Priority Mail only — pick Priority Mail, or switch the packaging to a Custom box.'
  }
  if (r.includes('license_number') && r.includes('invalid')) {
    return 'USPS rejected the export license / ITN. Check the AES citation on the shipment’s customs block.'
  }
  // Infrastructure / transient
  if (r.includes('remote host terminated') || r.includes('handshake')
          || r.includes('request cancelled') || r.includes('timed out')) {
    return 'USPS carrier endpoint was temporarily unreachable. Please retry in a moment.'
  }
  // Idempotency (shouldn’t surface — internal bug if it does)
  if (r.includes('idempotency-key provided was invalid') || r.includes('800001')) {
    return 'USPS rejected the duplicate-request guard key. Please retry; contact support if it persists.'
  }
  // Catalog + credentials
  if (r.includes('credentials aren’t verified') || r.includes('not authorized on this account')) {
    return 'This USPS account isn’t authorised with Stamps.com. Reconnect it in Settings → Carriers.'
  }
  if (r.includes('usps retired this service')) {
    // Already friendly from our pre-check; pass through.
    return raw.trim()
  }
  return null
}

/** True when the raw text came from the Stamps.com / SERA connector —
 *  used to route through the Stamps humanizer. Covers the connector-side
 *  prefixes we emit AND the server-side identifiers inside SERA's JSON
 *  response (so a message that reaches the FE without our wrapper still
 *  gets humanised). */
const isStampsRaw = (raw: string) =>
  /\bSTAMPS\b/.test(raw)
  || /\bSERA\b/.test(raw)
  || /stampsendicia\.com/.test(raw)
  || /\berror_reference_id\b/.test(raw)

/** A short, readable one-liner for the orders table. */
export function summarizeCarrierError(raw?: string | null): string {
  if (!raw) return 'Label failed'
  // Carrier-specific humanizers run FIRST. Known Stamps.com error
  // patterns map to actionable sentences; everything else falls through
  // to the generic extraction below.
  if (isStampsRaw(raw)) {
    const friendly = humanizeStampsError(raw)
    if (friendly) return friendly
  }
  const msgs = extractMessages(raw)
  if (msgs.length) {
    // Even for the extracted-message path, run the Stamps humanizer on
    // the joined text so a nested error_message triggers mapping.
    const joined = fixSentenceSpacing(msgs.join(' · '))
    if (isStampsRaw(raw)) {
      const friendly = humanizeStampsError(joined)
      if (friendly) return friendly
    }
    return joined
  }
  // No JSON messages — strip our wrapper prefix and any HTTP/transaction noise.
  return fixSentenceSpacing(raw
    .replace(/^The carrier rejected[^:]*:\s*/i, '')
    // Connector prefix shapes we emit:
    //   FEDEX createShipment HTTP 400           (FedEx)
    //   UPS createShipment HTTP 503             (UPS)
    //   DHL createShipment HTTP 429             (DHL)
    //   STAMPS createShipmentSera[pkg 1/1] HTTP 400   (SERA — has method suffix + piece bracket)
    //   STAMPS getRatesSera[pkg 1/1] HTTP 400         (SERA rate flow)
    // Allow optional \w+ after createShipment / any carrier verb +
    // optional [...] bracket, before the HTTP token.
    .replace(/\b[A-Z]+ \w+(?:\[[^\]]*\])? HTTP \d+:?\s*/i, '')
    // From the first brace on is carrier JSON — also when the stored message
    // was cut off mid-blob and never closes.
    .replace(/\{.*$/s, '')
    .trim()
    .slice(0, 160)) || 'Label failed'
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
