import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Sprint 31 — validate a delivery address against a carrier's own
 * database. Backend picks between UPS AVS, FedEx AV, DHL address-validate,
 * and SWSIM CleanseAddress based on {@code carrierCode}.
 */
export interface AddressValidationRequest {
  carrierCode: string
  customerNo?: string | null
  name?: string
  company?: string
  addressLine1: string
  addressLine2?: string
  addressLine3?: string
  city: string
  state?: string
  postalCode: string
  countryCode: string
}

export interface SuggestedAddress {
  name?: string | null
  addressLine1?: string | null
  addressLine2?: string | null
  addressLine3?: string | null
  city?: string | null
  state?: string | null
  postalCode?: string | null
  countryCode?: string | null
}

/**
 * matchLevel drives the UI badge:
 *   EXACT         — green, ready to ship.
 *   CORRECTED     — amber, suggested address available; review before shipping.
 *   AMBIGUOUS     — amber, carrier returned multiple candidates.
 *   NOT_FOUND     — red, carrier couldn't find this address.
 *   NOT_SUPPORTED — grey, no credentials or the connector didn't wire this endpoint.
 *   ERROR         — red, carrier or connection failure — see message.
 */
export interface AddressValidationResponse {
  carrierCode: string
  valid: boolean
  matchLevel: 'EXACT' | 'CORRECTED' | 'AMBIGUOUS' | 'NOT_FOUND' | 'NOT_SUPPORTED' | 'ERROR'
  classification: 'RESIDENTIAL' | 'COMMERCIAL' | 'UNKNOWN'
  suggested: SuggestedAddress | null
  warnings: string[]
  message: string
}

export const addressValidationService = {
  // Sprint 51 AC-L2 canonical route. Legacy `/address/validate` still works
  // (backend logs a WARN and dispatches to the same service) but is removed
  // in Sprint 53.
  validate: (request: AddressValidationRequest) =>
    apiClient.post<ApiResponse<AddressValidationResponse>>('/addresses/validate/carrier', request),
}
