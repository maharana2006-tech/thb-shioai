import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface SavedRecipient {
  id?: number
  ownerCustomerNo?: string | null
  name: string
  company?: string | null
  phone?: string | null
  phoneCountryCode?: string | null
  email?: string | null
  addressLine1: string
  addressLine2?: string | null
  addressLine3?: string | null
  city: string
  state?: string | null
  postalCode: string
  countryCode: string
  residential?: boolean | null
  tag?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export const recipientBookService = {
  /** Fuzzy substring search. Capped server-side at 25 hits. */
  search: async (q: string, customerNo?: string | null): Promise<SavedRecipient[]> => {
    if (!q || q.trim().length < 2) return []
    const params = new URLSearchParams({ q: q.trim() })
    if (customerNo) params.set('customerNo', customerNo)
    const response = await apiClient.get<ApiResponse<SavedRecipient[]>>(
      `/recipients/search?${params.toString()}`,
    )
    return Array.isArray(response.data) ? response.data : []
  },

  /** Idempotent create — a duplicate (same name + street + postal for
   *  the same owner) returns the existing row unchanged. */
  save: (recipient: SavedRecipient) =>
    apiClient.post<ApiResponse<SavedRecipient>>('/recipients', recipient),

  update: (id: number, recipient: SavedRecipient) =>
    apiClient.put<ApiResponse<SavedRecipient>>(`/recipients/${id}`, recipient),

  remove: (id: number) => apiClient.delete<ApiResponse<void>>(`/recipients/${id}`),

  /** The Address book page, paged. customerNo omitted = every entry for an
   *  admin; a client-scoped user always gets their own client's book. */
  list: (params: { q?: string; customerNo?: string | null; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams()
    if (params.q?.trim()) query.set('q', params.q.trim())
    if (params.customerNo) query.set('customerNo', params.customerNo)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 25))
    return apiClient.get<ApiResponse<RecipientPage>>(`/recipients?${query.toString()}`)
  },
}

/** Spring's Page, as the list endpoint returns it. */
export interface RecipientPage {
  content: SavedRecipient[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** True when the server answered a save with the entry it already had. */
export const isDuplicateSave = (message?: string | null) =>
  !!message && message.toLowerCase().includes('already exists')
