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
}
