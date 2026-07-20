import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { CarrierAccountRef } from './accountRefService'

/** A postal address — used for both ship-from and return blocks. */
export interface Address {
  name?: string | null
  line1?: string | null
  line2?: string | null
  city?: string | null
  state?: string | null
  zip?: string | null
  country?: string | null
  phone?: string | null
}

/** Client (customer) master data — orders link via clientCode. */
export interface Client {
  id: number
  clientCode: string
  name: string
  email: string | null
  phone: string | null
  status: 'ACTIVE' | 'INACTIVE' | string
  /** Origin printed in the label FROM block. */
  shipFrom: Address | null
  /** Where returns go; null when it mirrors shipFrom. */
  returnAddress: Address | null
  returnSameAsShipFrom: boolean
  createdAt: string | null
  updatedAt: string | null
  carrierAccounts: CarrierAccountRef[]
  orderCount: number
}

export interface ClientUpsertPayload {
  clientCode: string
  name: string
  email?: string
  phone?: string
  shipFrom?: Address
  returnAddress?: Address
  returnSameAsShipFrom?: boolean
}

export interface ClientPage {
  content: Client[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
}

export interface ClientListParams {
  search?: string
  /** ACTIVE | INACTIVE */
  status?: string
  /** UPS | FEDEX | USPS */
  carrier?: string
  /** YES | NO */
  hasOrders?: string
  /** column contains-filters */
  code?: string
  name?: string
  city?: string
  /** code | name | orderCount | created */
  sortBy?: string
  sortDirection?: 'ASC' | 'DESC'
  page?: number
  size?: number
}

export const clientService = {
  listClients: (params: ClientListParams = {}) => {
    const query = new URLSearchParams()
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 50))
    query.set('sortBy', params.sortBy ?? 'code')
    query.set('sortDirection', params.sortDirection ?? 'ASC')
    if (params.search?.trim()) query.set('search', params.search.trim())
    if (params.status) query.set('status', params.status)
    if (params.carrier) query.set('carrier', params.carrier)
    if (params.hasOrders) query.set('hasOrders', params.hasOrders)
    if (params.code?.trim()) query.set('code', params.code.trim())
    if (params.name?.trim()) query.set('name', params.name.trim())
    if (params.city?.trim()) query.set('city', params.city.trim())

    return apiClient.get<ApiResponse<ClientPage>>(`/clients?${query.toString()}`)
  },

  getClient: (clientCode: string) => {
    return apiClient.get<ApiResponse<Client>>(`/clients/${encodeURIComponent(clientCode)}`)
  },

  createClient: (payload: ClientUpsertPayload) => {
    return apiClient.post<ApiResponse<Client>>('/clients', payload)
  },

  updateClient: (clientCode: string, payload: ClientUpsertPayload) => {
    return apiClient.put<ApiResponse<Client>>(`/clients/${encodeURIComponent(clientCode)}`, payload)
  },

  toggleActive: (clientCode: string) => {
    return apiClient.post<ApiResponse<Client>>(`/clients/${encodeURIComponent(clientCode)}/toggle-active`)
  },

  deleteClient: (clientCode: string) => {
    return apiClient.delete<ApiResponse<void>>(`/clients/${encodeURIComponent(clientCode)}`)
  },

  listClientAccounts: async (clientCode: string): Promise<CarrierAccountRef[]> => {
    const response = await apiClient.get<ApiResponse<CarrierAccountRef[]>>(
      `/clients/${encodeURIComponent(clientCode)}/carrier-accounts`
    )
    return Array.isArray(response.data) ? response.data : []
  },
}
