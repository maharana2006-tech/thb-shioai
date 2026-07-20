import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { Address } from './clientService'

/** A single line on an order's customs declaration. */
export interface CustomsItem {
  id?: number
  description: string
  hsCode?: string | null
  countryOfOrigin?: string | null
  quantity: number
  unitValue?: number | null
  weight?: number | null
  sku?: string | null
}

/** An order's full customs declaration (importer of record + goods). */
export interface OrderCustoms {
  id?: number
  orderNo?: string
  importer: Address | null
  importerCompany?: string | null
  importerTaxId?: string | null
  importerVat?: string | null
  importerEori?: string | null
  incoterms?: string | null
  reasonForExport?: string | null
  currency?: string | null
  weightUnit?: string | null
  notes?: string | null
  items: CustomsItem[]
  createdAt?: string | null
  updatedAt?: string | null
}

export interface OrderCustomsPayload {
  importer?: Address
  importerCompany?: string
  importerTaxId?: string
  importerVat?: string
  importerEori?: string
  incoterms?: string
  reasonForExport?: string
  currency?: string
  weightUnit?: string
  notes?: string
  items: CustomsItem[]
}

export const customsService = {
  /** Returns the declaration, or null when the order has none yet. */
  getCustoms: async (orderNo: string | number): Promise<OrderCustoms | null> => {
    const response = await apiClient.get<ApiResponse<OrderCustoms | null>>(
      `/orders/${encodeURIComponent(String(orderNo))}/customs`
    )
    return response.data ?? null
  },

  upsertCustoms: (orderNo: string | number, payload: OrderCustomsPayload) => {
    return apiClient.put<ApiResponse<OrderCustoms>>(
      `/orders/${encodeURIComponent(String(orderNo))}/customs`,
      payload
    )
  },
}
