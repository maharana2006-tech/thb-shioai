import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface ManifestRequest {
  carrierCode: string
  customerNo?: string | null
  trackingNumbers: string[]
  closeDate?: string  // YYYY-MM-DD
  addressName?: string
  addressLine1?: string
  addressLine2?: string
  city?: string
  state?: string
  postalCode?: string
  countryCode?: string
}

/**
 * status drives the UI:
 *   MANIFESTED    — green, manifest ID populated + optional PDF URL / base64.
 *   NOT_SUPPORTED — grey, DHL or missing credentials.
 *   ERROR         — red, carrier rejected or connection failed.
 */
export interface ManifestResponse {
  carrierCode: string
  manifestId: string | null
  manifestPdfUrl: string | null
  manifestPdfBase64: string | null
  trackingCount: number
  status: 'MANIFESTED' | 'NOT_SUPPORTED' | 'ERROR'
  message: string
}

export const manifestService = {
  closeOut: (request: ManifestRequest) =>
    apiClient.post<ApiResponse<ManifestResponse>>('/manifests', request),
}
