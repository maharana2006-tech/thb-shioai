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

export interface ShipmentValidationResult {
  /** Top-level UX signal:
   *    PASS — no errors, address (if called) is EXACT or not applicable.
   *    WARN — no local errors but address is CORRECTED / AMBIGUOUS or
   *           local warnings are present.
   *    FAIL — at least one local error, or address is NOT_FOUND / ERROR. */
  overall: 'PASS' | 'WARN' | 'FAIL'
  message: string
  localErrors: ShipmentValidationIssue[]
  localWarnings: ShipmentValidationIssue[]
  skipped: ShipmentValidationCheckStatus[]
  /** Present when the carrier supports validateAddress AND local checks
   *  passed. Reuses the existing AddressValidationResponse type so the
   *  suggested-address apply flow keeps working unchanged. */
  address: AddressValidationResponse | null
  international: boolean
}

/**
 * Payload matches the backend ManualShipmentRequest shape (sender +
 * recipient + service + package + weight + optional intl block + DG +
 * signature / insurance). Untyped here because it's built dynamically
 * from NewShipmentPage state; the backend @RequestBody deserialiser is
 * the source of truth for accepted fields.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- payload
// is the same dynamic shape as the /orders/manual-label body; there's
// no static ManualShipmentPayload interface that carries the intl /
// DG / signature spread on the FE.
export type ShipmentValidationPayload = any

export const shipmentValidationService = {
  validate: (payload: ShipmentValidationPayload) =>
    apiClient.post<ApiResponse<ShipmentValidationResult>>(
      '/shipments/validate',
      payload,
    ),
}
