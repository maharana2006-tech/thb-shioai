import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** One entry in the curated UN number directory (backend Sprint 26). */
export interface UnNumberEntry {
  unNumber: string
  properShippingName: string
  hazardClass: string
  defaultPackingGroup: string | null
  notes: string | null
}

/** One dangerous commodity line item — mirrors backend DangerousCommodityDTO. */
export interface DangerousCommodity {
  unNumber: string
  properShippingName: string
  hazardClass: string
  packingGroup?: string | null
  quantity: number
  quantityUnit: string
  limitedQuantity?: boolean | null
  packageCount?: number | null
}

/** Wire-shape DG block sent on the shipment payload — mirrors backend
 *  DangerousGoodsBlockDTO. */
export interface DangerousGoodsBlock {
  regulationSet: 'IATA' | 'ADR' | 'DOT'
  accessibility?: 'ACCESSIBLE' | 'INACCESSIBLE' | null
  cargoAircraftOnly?: boolean | null
  emergencyContactName: string
  emergencyContactPhone: string
  emergencyResponseContract?: string | null
  signatoryName: string
  signatoryTitle?: string | null
  commodities: DangerousCommodity[]
}

export const dgService = {
  /**
   * Search the curated UN number directory — {@code UN\d{4}} prefix OR
   * proper-shipping-name substring, case-insensitive. Capped at 25.
   */
  search: async (q: string): Promise<UnNumberEntry[]> => {
    if (!q || q.trim().length < 2) return []
    const response = await apiClient.get<ApiResponse<UnNumberEntry[]>>(
      `/dg/un/search?q=${encodeURIComponent(q.trim())}`,
    )
    return Array.isArray(response.data) ? response.data : []
  },

  /** Exact lookup — case-insensitive on the UN prefix. Returns null when
   *  not in the curated set. */
  byNumber: async (unNumber: string): Promise<UnNumberEntry | null> => {
    try {
      const response = await apiClient.get<ApiResponse<UnNumberEntry>>(
        `/dg/un/${encodeURIComponent(unNumber)}`,
      )
      return response.data ?? null
    } catch {
      return null
    }
  },
}
