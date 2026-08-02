/**
 * International-shipment defaults for a carrier account. Wire codes only —
 * the backend column stores whatever we send here, so keep this in sync with
 * anything on the Java side that reads shipping_purpose / clearance_option.
 */

/** Purpose-of-shipping enum accepted by most carrier APIs. */
export const SHIPPING_PURPOSES = [
  { value: 'SALE',              label: 'Sale' },
  { value: 'GIFT',              label: 'Gift' },
  { value: 'SAMPLE',            label: 'Sample' },
  { value: 'REPAIR_AND_RETURN', label: 'Repair and return' },
  { value: 'DOCUMENTS',         label: 'Documents' },
  { value: 'MERCHANDISE',       label: 'Merchandise' },
  { value: 'PERSONAL_USE',      label: 'Personal use' },
  { value: 'RETURN',            label: 'Return' },
] as const

export type ShippingPurpose = typeof SHIPPING_PURPOSES[number]['value']

/**
 * Customs clearance option per carrier. Names differ between UPS / FedEx /
 * USPS in a way the carriers care about at the API — SENDER on UPS is
 * spelled the same as SENDER on FedEx but RECEIVER (UPS) vs RECIPIENT
 * (FedEx) differ, so we return the per-carrier list rather than a shared
 * mapping. Returns an empty array when the carrier isn't recognised so the
 * UI can render an empty picker with a "not modelled" hint.
 */
export type ClearanceOption = { value: string; label: string }

const CLEARANCE_BY_CARRIER: Record<string, ReadonlyArray<ClearanceOption>> = {
  UPS: [
    { value: 'SENDER',      label: 'Sender pays' },
    { value: 'RECEIVER',    label: 'Receiver pays' },
    { value: 'THIRD_PARTY', label: 'Third party' },
  ],
  FEDEX: [
    { value: 'SENDER',      label: 'Sender pays' },
    { value: 'RECIPIENT',   label: 'Recipient pays' },
    { value: 'THIRD_PARTY', label: 'Third party' },
  ],
  USPS: [
    { value: 'DDU', label: 'DDU — Duties on Delivery' },
    { value: 'DDP', label: 'DDP — Duties Paid' },
  ],
}

export function clearanceOptionsForCarrier(carrier: string | null | undefined): ReadonlyArray<ClearanceOption> {
  if (!carrier) return []
  return CLEARANCE_BY_CARRIER[carrier.toUpperCase()] ?? []
}

/** Human-readable label for a persisted value. Falls back to the raw value
 *  when the code isn't in the seeded list — useful for table cells that
 *  should never display "undefined" for a legacy row. */
export function purposeLabel(value: string | null | undefined): string {
  if (!value) return ''
  return SHIPPING_PURPOSES.find((p) => p.value === value)?.label ?? value
}

export function clearanceLabel(carrier: string | null | undefined, value: string | null | undefined): string {
  if (!value) return ''
  return clearanceOptionsForCarrier(carrier).find((o) => o.value === value)?.label ?? value
}
