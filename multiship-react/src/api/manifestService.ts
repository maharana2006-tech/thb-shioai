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
 *   PARTIAL       — amber, some fleet manifests succeeded but not all (FDX-G2).
 *   NOT_SUPPORTED — grey, DHL or missing credentials.
 *   ERROR         — red, carrier rejected or connection failed.
 *
 * FDX-G2 response fields (both nullable for back-compat with single-fleet
 * submissions from pre-FDX-G callers):
 *   · manifests — populated when the backend split the trackings across
 *     multiple fleets (typically FedEx Ground + Express mixed in one
 *     submission). Callers should read this list preferentially; the flat
 *     top-level manifestId/PDF fields are null in that case.
 *   · failedToClassify — trackings excluded from the manifest because their
 *     fleet couldn't be classified via the Code Maps SHIPVIA chain.
 *     Operator re-runs after fixing the mapping.
 */
export interface ManifestResponse {
  carrierCode: string
  manifestId: string | null
  manifestPdfUrl: string | null
  manifestPdfBase64: string | null
  trackingCount: number
  /** EMPTY (V135) — nothing open to close for that carrier/day. */
  status: 'MANIFESTED' | 'PARTIAL' | 'NOT_SUPPORTED' | 'ERROR' | 'EMPTY'
  message: string
  manifests?: ManifestEntry[] | null
  failedToClassify?: string[] | null
}

/** V135 — close a whole day for one carrier without listing trackings. */
export interface CloseDayRequest {
  carrierCode: string
  customerNo?: string | null
  warehouseCode?: string | null
  closeDate?: string  // YYYY-MM-DD; omit = today
}

/** V135 — one row of the pickup/close audit log. */
export interface EodEvent {
  id: number
  kind: 'PICKUP' | 'CLOSEOUT'
  carrierCode: string
  accountNumber: string | null
  customerNo: string | null
  warehouseCode: string | null
  eventDate: string | null
  reference: string | null
  trackingCount: number
  status: string
  message: string | null
  source: 'MANUAL' | 'SCHEDULED'
  createdBy: string | null
  createdAt: string
}

export interface ManifestEntry {
  /** GROUND | EXPRESS. */
  fleet: 'GROUND' | 'EXPRESS'
  manifestId: string | null
  manifestPdfUrl: string | null
  manifestPdfBase64: string | null
  trackingCount: number
  status: 'MANIFESTED' | 'ERROR' | 'NOT_SUPPORTED'
  message: string
  trackingNumbers: string[]
}

export const manifestService = {
  closeOut: (request: ManifestRequest) =>
    apiClient.post<ApiResponse<ManifestResponse>>('/manifests', request),

  /** V135 — close a whole day for one carrier (backend gathers the labels). */
  closeOutDay: (request: CloseDayRequest) =>
    apiClient.post<ApiResponse<ManifestResponse>>('/manifests/close-day', request),

  /** V135 — recent pickup/close events for the activity table. */
  recentEvents: (limit = 50) =>
    apiClient.get<ApiResponse<EodEvent[]>>(`/manifests/events?limit=${limit}`),
}
