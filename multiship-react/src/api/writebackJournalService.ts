/**
 * D1 — client for /api/v1/admin/writeback-journal. Drives
 * /settings/writeback-journal. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export type WritebackJournalStatus = 'PENDING' | 'OK' | 'SKIPPED' | 'FAILED'
export type WritebackJournalMode = 'GENERATE' | 'CLEAR'

export interface WritebackJournalRow {
  id: number
  connectionName: string
  systemType: string | null
  mode: WritebackJournalMode
  status: WritebackJournalStatus
  clientCode: string | null
  orderNo: number | null
  trackingNumber: string | null
  source: string | null
  channel: string | null
  ackStatus: string | null
  ackDetail: string | null
  errorMessage: string | null
  latencyMs: number | null
  attemptNumber: number
  retryOfId: number | null
  createdAt: string
  updatedAt: string
}

export interface WritebackJournalPage {
  items: WritebackJournalRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface WritebackJournalFilter {
  connectionName?: string
  status?: WritebackJournalStatus | ''
  mode?: WritebackJournalMode | ''
  orderNo?: number | ''
  page?: number
  size?: number
}

const base = '/admin/writeback-journal'

export const writebackJournalService = {
  async list(filter: WritebackJournalFilter = {}): Promise<WritebackJournalPage> {
    const qs = new URLSearchParams()
    if (filter.connectionName) qs.set('connectionName', filter.connectionName)
    if (filter.status) qs.set('status', filter.status)
    if (filter.mode) qs.set('mode', filter.mode)
    if (filter.orderNo !== '' && filter.orderNo != null) qs.set('orderNo', String(filter.orderNo))
    qs.set('page', String(filter.page ?? 0))
    qs.set('size', String(filter.size ?? 50))
    const res = await apiClient.get<ApiResponse<WritebackJournalPage>>(`${base}?${qs.toString()}`)
    return res.data ?? { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
  },

  async retry(id: number): Promise<void> {
    await apiClient.post<ApiResponse<{ retriedFrom: number }>>(`${base}/${id}/retry`)
  },
}
