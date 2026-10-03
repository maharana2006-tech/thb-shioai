/**
 * C4 — client for the DB-driven carrier registry at /api/v1/carriers/known.
 * Backed by the `carrier_alias` seed. Adding a new carrier + display label
 * is one INSERT + backend restart; the FE picks it up on next fetch.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface KnownCarrier {
  code: string
  label: string
}

export const carrierRegistryService = {
  async listKnown(): Promise<KnownCarrier[]> {
    const res = await apiClient.get<ApiResponse<KnownCarrier[]>>('/carriers/known')
    return res.data ?? []
  },
}
