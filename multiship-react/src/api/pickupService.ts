import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface PickupRequest {
  carrierCode: string
  customerNo?: string | null
  pickupDate: string          // YYYY-MM-DD
  pickupWindowStart?: string  // HH:mm
  pickupWindowEnd?: string    // HH:mm
  contactName: string
  contactPhone: string
  addressLine1: string
  addressLine2?: string
  city: string
  state?: string
  postalCode: string
  countryCode: string
  packageCount: number
  totalWeight: number
  weightUnit?: 'LB' | 'KG'
  specialInstructions?: string
}

/**
 * status drives the UI:
 *   SCHEDULED     — green, confirmation number populated.
 *   NOT_SUPPORTED — grey, missing credentials or connector doesn't wire pickup.
 *   ERROR         — red, carrier rejected or connection failed.
 */
export interface PickupResponse {
  carrierCode: string
  confirmationNumber: string | null
  scheduledDate: string | null
  pickupWindowStart: string | null
  pickupWindowEnd: string | null
  status: 'SCHEDULED' | 'NOT_SUPPORTED' | 'ERROR'
  message: string
}

export const pickupService = {
  schedule: (request: PickupRequest) =>
    apiClient.post<ApiResponse<PickupResponse>>('/pickups', request),
}
