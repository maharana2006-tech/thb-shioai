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
 * USPS / DHL in a way the carriers care about at the API — SENDER on UPS is
 * spelled the same as SENDER on FedEx but RECEIVER (UPS) vs RECIPIENT
 * (FedEx) differ, and DHL uses Incoterms codes (DAP/DDP/EXW) instead of a
 * bill-to enum. We return the per-carrier list rather than a shared mapping
 * so the picker offers exactly what the connector envelope will accept.
 * Returns an empty array when the carrier isn't recognised so the UI can
 * render an empty picker with a "not modelled" hint.
 *
 * DHL Express (F6-F): the connector maps clearanceOption / incoterms to
 * the `incoterm` field on the export declaration. The 3 codes here are the
 * ones DHL Express commonly quotes for parcel-level shipments — the wider
 * Incoterms 2020 vocabulary (FCA / CPT / CIP / DPU / …) is freight-oriented
 * and not exposed here to avoid mis-selection. Add more values as
 * operators ask for them; the connector passes anything through verbatim.
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
  DHL: [
    { value: 'DAP', label: 'DAP — Delivered At Place (receiver pays duties)' },
    { value: 'DDP', label: 'DDP — Delivered Duty Paid (sender pays duties)' },
    { value: 'EXW', label: 'EXW — Ex Works (receiver arranges pickup)' },
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

/**
 * US Foreign Trade Regulations §30.37 exemption codes. One of these — OR
 * an AES ITN filed with US Census — is required on every US-origin export
 * to a non-Canada destination valued ≥ $2,500 USD (per Schedule B code).
 * Without one, FedEx auto-applies 30.37(a) and rejects the shipment on
 * the value threshold.
 *
 *   30.37(a) — sub-$2,500 shipments. Legal ONLY under that threshold.
 *   30.37(h) — tools of trade / temporary export (return within 12 months).
 *   30.36    — shipments to Canada (bilateral exemption).
 *
 * The values here mirror the backend wire codes (IntlShipmentBlockDTO
 * .ftrExemption). Labels stay statute-accurate — operators pick from a
 * closed vocabulary and the connector maps to the carrier's own statement
 * format.
 */
export const FTR_EXEMPTIONS = [
  { value: 'NO_EEI_30_37_a', label: 'NOEEI §30.37(a) — value under $2,500 USD' },
  { value: 'NO_EEI_30_37_h', label: 'NOEEI §30.37(h) — tools of trade / temporary export' },
  { value: 'NO_EEI_30_36',   label: 'NOEEI §30.36 — export to Canada' },
] as const

export type FtrExemption = typeof FTR_EXEMPTIONS[number]['value']

/** Human label for a persisted FTR wire code. Falls back to the raw value
 *  so unknown codes still render (rather than "undefined"). */
export function ftrExemptionLabel(value: string | null | undefined): string {
  if (!value) return ''
  return FTR_EXEMPTIONS.find((e) => e.value === value)?.label ?? value
}

/** US FTR §30.37(a) monetary threshold — mirrors IntlShipmentValidator
 *  .EEI_THRESHOLD_USD on the backend. Frontend uses it to gate the EEI
 *  field visibility and validation banner. */
export const EEI_THRESHOLD_USD = 2500
