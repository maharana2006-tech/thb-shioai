/**
 * V117 — read-only clients for /api/v1/admin/refdata/*. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface ReasonForExportRow {
  code: string
  label: string
  sortOrder: number | null
}

export interface IsoCurrencyRow {
  code: string
  name: string | null
}

export const refdataService = {
  reasons: () =>
    apiClient
      .get<ApiResponse<ReasonForExportRow[]>>('/admin/refdata/reasons-for-export')
      .then((res) => res.data ?? []),

  currencies: () =>
    apiClient
      .get<ApiResponse<IsoCurrencyRow[]>>('/admin/refdata/currencies')
      .then((res) => res.data ?? []),
}
