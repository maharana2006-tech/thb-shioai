/**
 * V113 — client for /api/v1/admin/alerts/history. Drives
 * /settings/alerts-history. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface AlertHistoryRow {
  id: number
  firedAt: string
  source: string
  templateKey: string | null
  targetOrderNo: number | null
  tenantCode: string | null
  importBatchId: number | null
  reason: string | null
}

export interface AlertHistoryPage {
  items: AlertHistoryRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AlertHistoryFilter {
  source?: string
  tenantCode?: string
  orderNo?: number | ''
  page?: number
  size?: number
}

const BASE = '/admin/alerts/history'

export const alertHistoryService = {
  async list(filter: AlertHistoryFilter = {}): Promise<AlertHistoryPage> {
    const qs = new URLSearchParams()
    if (filter.source) qs.set('source', filter.source)
    if (filter.tenantCode) qs.set('tenantCode', filter.tenantCode)
    if (filter.orderNo !== '' && filter.orderNo != null) qs.set('orderNo', String(filter.orderNo))
    qs.set('page', String(filter.page ?? 0))
    qs.set('size', String(filter.size ?? 50))
    const res = await apiClient.get<ApiResponse<AlertHistoryPage>>(`${BASE}?${qs.toString()}`)
    return res.data ?? { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
  },
}
