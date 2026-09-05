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
  /** US FTR §30.37 exemption wire code — see utils/customsOptions.ts FTR_EXEMPTIONS. */
  ftrExemption?: string | null
  /** AES ITN filed with US Census (e.g. "X20260101123456"). */
  aesCitation?: string | null
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
  /**
   * US FTR §30.37 exemption wire code — mutually exclusive with
   * {@link aesCitation}. Enumerated in utils/customsOptions.ts
   * FTR_EXEMPTIONS. Populated on US-origin exports where the operator
   * claims an exemption instead of filing AES.
   */
  ftrExemption?: string
  /**
   * AES ITN filed with US Census. Required — in lieu of
   * {@link ftrExemption} — on US-origin exports valued ≥ $2,500 USD
   * per Schedule B code to non-Canada destinations.
   */
  aesCitation?: string
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
