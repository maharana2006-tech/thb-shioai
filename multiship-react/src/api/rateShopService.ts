import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { DangerousGoodsBlock } from './dgService'

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

  /** G4 — internal ShippingService.id. Null when the vendor's serviceCode
   *  isn't in our catalog. */
  serviceId?: number | null
  /** G4 — what routing rules would do to this option at label time.
   *   KEEP    — no rule matches, option would be used as-is
   *   REROUTE — a rule would rewrite the service or warehouse
   *   BLOCK   — a rule would refuse label generation
   *  Null when no client scope was passed or the serviceId couldn't be resolved. */
  routingOutcome?: 'KEEP' | 'REROUTE' | 'BLOCK' | null
  routingRuleName?: string | null
  routingTargetCarrier?: string | null
  routingTargetServiceCode?: string | null
  routingTargetWarehouseId?: number | null
  routingBlockReason?: string | null
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
  /** Sprint 39 adds CACHE — options served from the backend rate cache
   *  (5-min TTL). The message field says "cached Xs ago" so the UI can
   *  render freshness. */
  source: 'LIVE' | 'CACHE' | 'STUB' | 'ERROR'
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
  /** Sprint 51 — business/company name (FedEx contact.companyName,
   *  UPS Shipper.Name, DHL companyName, USPS SWSIM <Company>). */
  shipperCompany?: string
  /** Sprint 51 — email for delivery notifications (FedEx
   *  contact.emailAddress, UPS EMailAddress, DHL email, USPS
   *  <EmailAddress>). */
  shipperEmail?: string
  shipperAddressLine1?: string
  shipperAddressLine2?: string
  shipperCity?: string
  shipperState?: string
  shipperPostalCode: string
  shipperCountryCode: string
  recipientName?: string
  recipientPhone?: string
  /** Sprint 51 — see shipperCompany. */
  recipientCompany?: string
  /** Sprint 51 — see shipperEmail. */
  recipientEmail?: string
  /** ISO dial code without the plus; connectors prepend it to recipientPhone
   *  at wire time. Match to backend ShipmentRequestDTO.recipientPhoneCountryCode. */
  recipientPhoneCountryCode?: string
  recipientAddressLine1?: string
  recipientAddressLine2?: string
  /** Third street line — JP/CN/IN long addresses. Optional; carriers accept 3+. */
  recipientAddressLine3?: string
  recipientCity?: string
  recipientState?: string
  recipientPostalCode: string
  recipientCountryCode: string
  recipientResidential?: boolean
  declaredValue?: number
  declaredValueCurrency?: string
  /**
   * Sprint 51 rate-shop gap-fill — surcharges that materially move the
   * quoted price. Prior to this the FE sent none of them, so the picker
   * consistently under-quoted anything with signature / insurance /
   * hazmat by $5-60+ per shipment.
   */
  /** ADULT / DIRECT / INDIRECT / NONE. Undefined = carrier default. */
  signatureOption?: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ADULT'
  /** Insurance amount + currency. Undefined = no insurance. */
  insuredValue?: number
  insuredValueCurrency?: string
  /** Hazmat block. Undefined = no DG surcharge. */
  dangerousGoods?: DangerousGoodsBlock
  /** True when this is a return label — some carriers rate returns differently. */
  isReturn?: boolean
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
