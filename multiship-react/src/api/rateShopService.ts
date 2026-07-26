import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * One priced service option — mirrors backend
 * {@code RateShopResponseDTO.RateOptionDTO}. Currency is always ISO-4217;
 * transitDays / estimatedDelivery are optional.
 */
export interface RateOption {
  carrierCode: string
  serviceCode: string
  serviceName: string | null
  totalAmount: number
  currency: string
  estimatedDelivery?: string | null
  transitDays?: number | null
}

/**
 * Per-carrier fan-out result. {@code source} is:
 *   LIVE  — the carrier returned N > 0 priced options.
 *   STUB  — no live credentials configured, or the carrier returned zero
 *           options for the lane. Not an error — just nothing to quote.
 *   ERROR — token acquisition failed, connector timed out, or the carrier
 *           API rejected the request.
 */
export interface CarrierRateStatus {
  carrierCode: string
  optionCount: number
  source: 'LIVE' | 'STUB' | 'ERROR'
  message: string
}

export interface RateShopResponse {
  options: RateOption[]
  carrierResults: CarrierRateStatus[]
}

/** Shipment envelope. Minimally-populated for rate shopping — the full
 *  ShipmentRequestDTO shape is defined server-side. */
export interface RateShopShipment {
  carrierCode?: string
  accountNumber?: string
  serviceType?: string
  packageType?: string
  weight: number
  weightUnit?: 'LB' | 'KG'
  length?: number
  width?: number
  height?: number
  dimUnit?: 'IN' | 'CM'
  shipperName?: string
  shipperPhone?: string
  shipperAddressLine1?: string
  shipperCity?: string
  shipperState?: string
  shipperPostalCode: string
  shipperCountryCode: string
  recipientName?: string
  recipientPhone?: string
  recipientAddressLine1?: string
  recipientCity?: string
  recipientState?: string
  recipientPostalCode: string
  recipientCountryCode: string
  recipientResidential?: boolean
  declaredValue?: number
  declaredValueCurrency?: string
}

export interface RateShopRequest {
  shipment: RateShopShipment
  /** Customer number so the service prefers the customer's carrier
   *  credentials over the platform account. */
  customerNo?: string | null
  /** Whitelist of carrier codes to fan out to. Null or empty = all four. */
  carriers?: string[]
}

export const rateShopService = {
  /**
   * Fan out a rate quote across every configured carrier and return the
   * merged list sorted cheapest-first. Backend never throws on carrier
   * failures — inspect {@code carrierResults} for per-carrier status.
   */
  quote: (request: RateShopRequest) =>
    apiClient.post<ApiResponse<RateShopResponse>>('/rate-shop', request),
}
