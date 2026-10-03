/**
 * V114 — client for /api/v1/admin/carrier-api-log. Drives
 * /settings/carrier-api-log. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface CarrierApiLogSummary {
  id: number
  requestId: string | null
  carrier: string
  method: string
  url: string
  statusCode: number | null
  latencyMs: number | null
  errorMessage: string | null
  orderNo: number | null
  tracking: string | null
  createdAt: string
}

export interface CarrierApiLogRow extends CarrierApiLogSummary {
  requestBody: string | null
  responseBody: string | null
}

export interface CarrierApiLogPage {
  items: CarrierApiLogSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface CarrierApiLogFilter {
  carrier?: string
  orderNo?: number | ''
  tracking?: string
  page?: number
  size?: number
}

const BASE = '/admin/carrier-api-log'

export const carrierApiLogService = {
  async list(filter: CarrierApiLogFilter = {}): Promise<CarrierApiLogPage> {
    const qs = new URLSearchParams()
    if (filter.carrier) qs.set('carrier', filter.carrier)
    if (filter.orderNo !== '' && filter.orderNo != null) qs.set('orderNo', String(filter.orderNo))
    if (filter.tracking) qs.set('tracking', filter.tracking)
    qs.set('page', String(filter.page ?? 0))
    qs.set('size', String(filter.size ?? 50))
    const res = await apiClient.get<ApiResponse<CarrierApiLogPage>>(`${BASE}?${qs.toString()}`)
    return res.data ?? { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
  },

  getFull: (id: number) =>
    apiClient
      .get<ApiResponse<CarrierApiLogRow>>(`${BASE}/${id}`)
      .then((res) => res.data as CarrierApiLogRow),
}
