/**
 * V112 — client for /api/v1/admin/carriers/platform. Drives
 * /settings/carriers-platform. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export type CarrierMode = 'LIVE' | 'TEST'

export interface CarrierPlatformRow {
  carrierCode: string
  displayName: string | null
  enabled: boolean
  mode: CarrierMode
  family: string | null
  updatedAt: string | null
}

const BASE = '/admin/carriers/platform'

export const carriersPlatformService = {
  list: () =>
    apiClient
      .get<ApiResponse<CarrierPlatformRow[]>>(BASE)
      .then((res) => res.data ?? []),

  update: (carrierCode: string, enabled?: boolean, mode?: CarrierMode) =>
    apiClient
      .put<ApiResponse<CarrierPlatformRow>>(
        `${BASE}/${encodeURIComponent(carrierCode)}`,
        { enabled, mode },
      )
      .then((res) => res.data as CarrierPlatformRow),
}
