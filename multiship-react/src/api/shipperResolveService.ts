/**
 * C2 — resolved ship-from defaults for the current user. Feeds the
 * /orders/new sender prefill. Any authenticated role; the backend
 * either returns the user's own tenant defaults (no clientCode) or
 * the addressed client's (if the user's tenant scope allows it).
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface ResolvedShipper {
  tenantCode: string | null
  name: string | null
  phone: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  countryCode: string | null
}

export const shipperResolveService = {
  async resolve(clientCode?: string): Promise<ResolvedShipper | null> {
    const qs = clientCode ? `?clientCode=${encodeURIComponent(clientCode)}` : ''
    const res = await apiClient.get<ApiResponse<ResolvedShipper>>(`/me/shipper-default${qs}`)
    return res.data ?? null
  },
}
