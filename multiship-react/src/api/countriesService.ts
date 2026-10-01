/**
 * V111 — read-only client for /api/v1/admin/countries. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface CountryRow {
  countryCode: string
  name: string | null
  isUsTerritory: boolean
  updatedAt: string | null
}

export const countriesService = {
  list: () =>
    apiClient
      .get<ApiResponse<CountryRow[]>>('/admin/countries')
      .then((res) => res.data ?? []),
}
