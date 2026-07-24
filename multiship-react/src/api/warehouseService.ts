import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { Address } from './clientService'

/** A ship-from location. PLATFORM warehouses are attachable to any client;
 *  CLIENT warehouses are private to their owner. */
export interface Warehouse {
  id: number
  code: string
  name: string
  address: Address | null
  ownerType: 'PLATFORM' | 'CLIENT' | string
  ownerClientCode: string | null
  active: boolean
  /** How many clients currently attach this warehouse (list view). */
  attachedClientCount: number | null
  createdAt: string | null
  updatedAt: string | null
}

export interface WarehouseUpsertPayload {
  code: string
  name: string
  address?: Address
  ownerType: 'PLATFORM' | 'CLIENT'
  /** Required when ownerType=CLIENT. */
  ownerClientCode?: string
  active?: boolean
}

export interface WarehousePage {
  content: Warehouse[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
}

export interface WarehouseListParams {
  search?: string
  /** PLATFORM | CLIENT */
  ownerType?: string
  ownerClientCode?: string
  /** YES | NO */
  active?: string
  /** code | name | owner | created */
  sortBy?: string
  sortDirection?: 'ASC' | 'DESC'
  page?: number
  size?: number
}

/** Client ↔ Warehouse link + per-client default flag. */
export interface ClientWarehouse {
  id: number
  clientCode: string
  warehouse: Warehouse | null
  isDefault: boolean
  createdAt: string | null
  updatedAt: string | null
}

export interface AttachWarehousePayload {
  warehouseCode: string
  makeDefault: boolean
}

export const warehouseService = {
  listWarehouses: (params: WarehouseListParams = {}) => {
    const query = new URLSearchParams()
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 50))
    query.set('sortBy', params.sortBy ?? 'code')
    query.set('sortDirection', params.sortDirection ?? 'ASC')
    if (params.search?.trim()) query.set('search', params.search.trim())
    if (params.ownerType) query.set('ownerType', params.ownerType)
    if (params.ownerClientCode?.trim()) query.set('ownerClientCode', params.ownerClientCode.trim())
    if (params.active) query.set('active', params.active)
    return apiClient.get<ApiResponse<WarehousePage>>(`/warehouses?${query.toString()}`)
  },

  getWarehouse: (code: string) =>
    apiClient.get<ApiResponse<Warehouse>>(`/warehouses/${encodeURIComponent(code)}`),

  createWarehouse: (payload: WarehouseUpsertPayload) =>
    apiClient.post<ApiResponse<Warehouse>>('/warehouses', payload),

  updateWarehouse: (code: string, payload: WarehouseUpsertPayload) =>
    apiClient.put<ApiResponse<Warehouse>>(`/warehouses/${encodeURIComponent(code)}`, payload),

  toggleActive: (code: string) =>
    apiClient.post<ApiResponse<Warehouse>>(`/warehouses/${encodeURIComponent(code)}/toggle-active`),

  deleteWarehouse: (code: string) =>
    apiClient.delete<ApiResponse<void>>(`/warehouses/${encodeURIComponent(code)}`),
}

export const clientWarehouseService = {
  listForClient: (clientCode: string) =>
    apiClient.get<ApiResponse<ClientWarehouse[]>>(
      `/clients/${encodeURIComponent(clientCode)}/warehouses`,
    ),

  attach: (clientCode: string, payload: AttachWarehousePayload) =>
    apiClient.post<ApiResponse<ClientWarehouse>>(
      `/clients/${encodeURIComponent(clientCode)}/warehouses`,
      payload,
    ),

  detach: (clientCode: string, warehouseCode: string) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/warehouses/${encodeURIComponent(warehouseCode)}`,
    ),

  setDefault: (clientCode: string, warehouseCode: string) =>
    apiClient.put<ApiResponse<ClientWarehouse>>(
      `/clients/${encodeURIComponent(clientCode)}/warehouses/${encodeURIComponent(warehouseCode)}/default`,
      {},
    ),
}
