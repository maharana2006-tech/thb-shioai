import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Sprint 52 — bindings for the `/settings/carrier-limits` admin page.
 * ADMIN-only CRUD over the `carrier_shipping_limit` catalog. Every
 * mutation invalidates the backend resolver's in-memory cache, so an
 * ops edit lands on the shipment-create path immediately (no wait for
 * the 5-min TTL).
 */

export interface CarrierShippingLimit {
  id: number
  carrierCode: string
  serviceCode: string | null
  scope: 'BOTH' | 'DOMESTIC' | 'INTERNATIONAL'
  direction: 'FORWARD' | 'RETURN' | null
  maxPackages: number
  maxCommodities: number | null
  maxTotalWeightLb: number | null
  freeDeclaredValue: number | null
  effectiveFrom: string | null
  effectiveUntil: string | null
  active: boolean
  notes: string | null
}

export interface CarrierShippingLimitPayload {
  carrierCode: string
  serviceCode?: string | null
  scope: 'BOTH' | 'DOMESTIC' | 'INTERNATIONAL'
  direction?: 'FORWARD' | 'RETURN' | null
  maxPackages: number
  maxCommodities?: number | null
  maxTotalWeightLb?: number | null
  freeDeclaredValue?: number | null
  active?: boolean
  notes?: string | null
}

export interface CarrierShippingLimitListParams {
  page?: number
  size?: number
}

function toQueryString(params: object): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === '') continue
    qs.set(k, String(v))
  }
  const s = qs.toString()
  return s ? `?${s}` : ''
}

const BASE = '/admin/carrier-shipping-limits'

export const carrierShippingLimitService = {
  list: (params: CarrierShippingLimitListParams = {}) =>
    apiClient.get<ApiResponse<CarrierShippingLimit[]>>(`${BASE}${toQueryString(params)}`),

  get: (id: number) =>
    apiClient.get<ApiResponse<CarrierShippingLimit>>(`${BASE}/${id}`),

  create: (payload: CarrierShippingLimitPayload) =>
    apiClient.post<ApiResponse<CarrierShippingLimit>>(BASE, payload),

  update: (id: number, payload: CarrierShippingLimitPayload) =>
    apiClient.put<ApiResponse<CarrierShippingLimit>>(`${BASE}/${id}`, payload),

  remove: (id: number) =>
    apiClient.delete<void>(`${BASE}/${id}`),
}
