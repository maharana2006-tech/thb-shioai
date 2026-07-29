import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** G3 — /api/v1/clients/{code}/warehouses/select-nearest. */
export interface WarehouseSelectionCandidate {
  warehouseId: number
  warehouseCode: string
  warehouseName: string
  warehouseCountry: string | null
  warehousePostal: string | null
  isDefault: boolean
  score: number
  sameCountry: boolean
  postalPrefixLength: number
  reason: string
}

export interface WarehouseSelectionResult {
  /** COUNTRY_AND_POSTAL | COUNTRY | ANY | NONE. */
  matchReason: string
  selectedWarehouseId: number | null
  selectedWarehouseCode: string | null
  selectedWarehouseName: string | null
  postalPrefixLength: number
  candidates: WarehouseSelectionCandidate[]
}

export interface WarehouseSelectionPayload {
  destCountry?: string | null
  destPostal?: string | null
}

export const warehouseSelectorService = {
  selectNearest: async (
    clientCode: string,
    payload: WarehouseSelectionPayload,
  ): Promise<WarehouseSelectionResult> => {
    const resp = await apiClient.post<ApiResponse<WarehouseSelectionResult>>(
      `/clients/${encodeURIComponent(clientCode)}/warehouses/select-nearest`,
      payload,
    )
    return resp.data as WarehouseSelectionResult
  },
}
