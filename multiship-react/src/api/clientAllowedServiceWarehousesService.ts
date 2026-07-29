import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface AllowedServiceWarehouses {
  clientCode: string
  serviceId: number
  /** Warehouse ids, sorted ascending. Empty = unrestricted (any warehouse). */
  warehouseIds: number[]
}

export interface ReplaceAllowedServiceWarehousesPayload {
  warehouseIds: number[]
}

/** Warehouse-gate on a single ClientAllowedService row (G1). */
export const clientAllowedServiceWarehousesService = {
  get: (clientCode: string, serviceId: number) =>
    apiClient.get<ApiResponse<AllowedServiceWarehouses>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}/warehouses`,
    ),

  replace: (clientCode: string, serviceId: number, payload: ReplaceAllowedServiceWarehousesPayload) =>
    apiClient.put<ApiResponse<AllowedServiceWarehouses>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}/warehouses`,
      payload,
    ),

  clear: (clientCode: string, serviceId: number) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}/warehouses`,
    ),
}
