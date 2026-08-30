import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { AddressValidationResponse } from './addressValidationService'

/**
 * Sprint 52 — server-side pre-flight for a manual shipment. Powers the
 * "Validate shipment" button on NewShipmentPage (formerly "Validate with
 * Carrier" which only sent recipient address fields). The request body
 * is the SAME shape as {@code POST /orders/manual-label} so the FE
 * builds one payload from form state and reuses it for both validation
 * and label generation.
 *
 * <p>Backend runs local guards (packaging compatibility, markup
 * required, customs, DG, allowlists) and, if all pass, calls the
 * carrier's own validateAddress as a partial substitute for a native
 * validateShipment.
 */
export interface ShipmentValidationIssue {
  /** Stable ErrorCode name (e.g. PACKAGE_NOT_ALLOWED_FOR_SERVICE,
   *  MARKUP_REQUIRED_FOR_CLIENT, VALIDATION_ERROR) or a snake_case
   *  string for validators that don't map to the enum. */
  code: string
  message: string
  /** Optional form-field hint the FE can use to highlight the offending
   *  input — 'recipient.postalCode', 'items[2].hsCode', 'weight', ... */
  field?: string | null
}

export interface ShipmentValidationCheckStatus {
  /** Short identifier — 'customs', 'packaging_compatibility', 'markup',
   *  'ship_to_allowlist', 'dangerous_goods', 'carrier_address_validate'. */
  name: string
  /** Why the check was skipped — 'domestic shipment', 'ad-hoc shipment', etc. */
  reason: string
}

export interface CarrierValidationSubResult {
  /** FEDEX | UPS | DHL | USPS. */
  carrierCode: string
  /** True when the carrier confirmed the shipment / address is deliverable. */
  valid: boolean
  /** EXACT | CORRECTED | AMBIGUOUS | NOT_FOUND | NOT_SUPPORTED | ERROR. */
  matchLevel: string
  /** SHIPMENT (carrier-native validate endpoint) | ADDRESS_ONLY
   *  (connector delegated to its address validator). MVP: all 4
   *  carriers are ADDRESS_ONLY; PR δ.1 upgrades FedEx + UPS. */
  kind: 'SHIPMENT' | 'ADDRESS_ONLY' | string
  warnings: string[]
  errors: string[]
  message: string
}

export interface ShipmentValidationResult {
  /** Top-level UX signal:
   *    PASS — no errors, carrier (if called) is EXACT or not applicable.
   *    WARN — no local errors but carrier is CORRECTED / AMBIGUOUS or
   *           local warnings are present.
   *    FAIL — at least one local error, or carrier is NOT_FOUND / ERROR. */
  overall: 'PASS' | 'WARN' | 'FAIL'
  message: string
  localErrors: ShipmentValidationIssue[]
  localWarnings: ShipmentValidationIssue[]
  skipped: ShipmentValidationCheckStatus[]
  /** @deprecated Sprint 52 PR δ — replaced by {@link carrier}. Always
   *  null on new backend responses; kept for stale-bundle back-compat. */
  address: AddressValidationResponse | null
  /** Sprint 52 PR δ — carrier's own opinion on the shipment. Null when
   *  local pre-flight failed (carrier hop skipped), when carrier isn't
   *  configured (no credentials), or when connector returns NOT_SUPPORTED.
   *  Drives the new secondary "Carrier check" section on the banner. */
  carrier: CarrierValidationSubResult | null
  international: boolean
}

/**
 * Payload matches the backend ManualShipmentRequest shape (sender +
 * recipient + service + package + weight + optional intl block + DG +
 * signature / insurance). Typed as `Record<string, unknown>` because
 * it's built dynamically from NewShipmentPage state via spread + the
 * backend @RequestBody deserialiser is the source of truth for
 * accepted fields — there's no static ManualShipmentPayload interface
 * on the FE that captures the intl / DG / signature conditional spread.
 */
export type ShipmentValidationPayload = Record<string, unknown>

export const shipmentValidationService = {
  validate: (payload: ShipmentValidationPayload) =>
    apiClient.post<ApiResponse<ShipmentValidationResult>>(
      '/shipments/validate',
      payload,
    ),
}
