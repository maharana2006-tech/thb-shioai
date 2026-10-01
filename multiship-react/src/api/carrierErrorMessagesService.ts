/**
 * V118 — read-only client for /api/v1/admin/carrier-error-messages. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface CarrierErrorMessageRow {
  id: number
  carrier: string | null
  matchAnyOf: string
  humanized: string
  sortOrder: number | null
  updatedAt: string | null
}

export const carrierErrorMessagesService = {
  list: () =>
    apiClient
      .get<ApiResponse<CarrierErrorMessageRow[]>>('/admin/carrier-error-messages')
      .then((res) => res.data ?? []),
}
