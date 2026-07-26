import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { RateShopShipment } from './rateShopService'

/**
 * Sprint 32 — estimate the total landed cost (freight + duties + taxes
 * + fees) for a cross-border shipment. Backend routes to UPS Landed
 * Cost, FedEx EDT, or DHL Duties + Taxes based on {@code carrierCode}.
 * USPS is domestic-only and always returns {@code source=NOT_SUPPORTED}.
 */
export interface LandedCostRequest {
  carrierCode: string
  customerNo?: string | null
  shipment: RateShopShipment & {
    intl?: unknown
    declaredValueCurrency?: string
  }
}

export interface LandedCostLine {
  description: string | null
  hsCode: string | null
  quantity: number | null
  declaredValue: number | null
  dutyAmount: number | null
  taxAmount: number | null
  currency: string | null
}

/**
 * source drives the UI:
 *   LIVE          — the carrier returned duties + taxes.
 *   NOT_SUPPORTED — domestic lane, USPS, or no credentials.
 *   ERROR         — carrier call failed; see message.
 */
export interface LandedCostResponse {
  carrierCode: string
  source: 'LIVE' | 'NOT_SUPPORTED' | 'ERROR'
  freightAmount: number | null
  dutyTotal: number | null
  taxTotal: number | null
  otherTotal: number | null
  grandTotal: number | null
  currency: string | null
  lineItems: LandedCostLine[]
  warnings: string[]
  message: string
}

export const landedCostService = {
  estimate: (request: LandedCostRequest) =>
    apiClient.post<ApiResponse<LandedCostResponse>>('/landed-cost', request),
}
