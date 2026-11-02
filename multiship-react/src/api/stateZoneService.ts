import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * G2 — country + state → IANA zone mapping (state_zone table).
 * Drives DST-aware offsets for carrier timestamps (FedEx
 * readyDateTimestamp today; UPS / DHL EOD when they land).
 */
export interface StateZone {
  countryCode: string
  stateCode: string
  zoneId: string
  displayName: string | null
  updatedAt: string | null
  updatedBy: string | null
}

export interface StateZoneUpsert {
  zoneId: string
  displayName?: string | null
}

const BASE = '/admin/state-zones'

export const stateZoneService = {
  list: async (): Promise<StateZone[]> => {
    const r = await apiClient.get<ApiResponse<StateZone[]>>(BASE)
    return r.data ?? []
  },

  upsert: (countryCode: string, stateCode: string, body: StateZoneUpsert) =>
    apiClient.put<ApiResponse<StateZone>>(
      `${BASE}/${encodeURIComponent(countryCode)}/${encodeURIComponent(stateCode)}`,
      body,
    ),

  remove: (countryCode: string, stateCode: string) =>
    apiClient.delete<ApiResponse<void>>(
      `${BASE}/${encodeURIComponent(countryCode)}/${encodeURIComponent(stateCode)}`,
    ),
}
