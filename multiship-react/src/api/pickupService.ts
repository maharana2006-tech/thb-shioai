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
  /**
   * FDX-F — which driver fleet the carrier dispatches for this pickup.
   * FedEx maps to FDXE / FDXG; UPS maps to ServiceCode 007 / 003; DHL
   * has one product (P) and USPS/SWSIM has no per-request service, so
   * both accept the field but no-op. Undefined falls to GROUND.
   */
  pickupServiceType?: PickupServiceType
  /**
   * DHL-8 — per-package default dimensions on the pickup body. DHL uses
   * dims to route the pickup to the right vehicle class (van vs truck).
   * Pre-fix, every package on the DHL pickup wire carried a hardcoded
   * 30 × 20 × 10 cm regardless of actual parcel size. All optional —
   * FedEx/UPS/USPS accept but no-op; when unset the DHL connector falls
   * back to the historical 30 × 20 × 10 cm default.
   */
  defaultLength?: number
  defaultWidth?: number
  defaultHeight?: number
  dimUnit?: 'CM' | 'IN'
}

export type PickupServiceType = 'GROUND' | 'EXPRESS' | 'INTERNATIONAL'

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
