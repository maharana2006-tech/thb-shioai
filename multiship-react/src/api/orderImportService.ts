import { apiClient, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

export interface OrderImportRow {
  rowNumber: number
  recipientName?: string | null
  recipientCompany?: string | null
  recipientPhone?: string | null
  recipientEmail?: string | null
  addressLine1?: string | null
  addressLine2?: string | null
  city?: string | null
  state?: string | null
  postalCode?: string | null
  countryCode?: string | null
  carrierCode?: string | null
  serviceType?: string | null
  packageType?: string | null
  weight?: number | null
  weightUnit?: string | null
  declaredValue?: number | null
  currency?: string | null
  reference?: string | null
  goodsDescription?: string | null
  errors: string[]
}

export interface OrderImportPreview {
  totalRows: number
  validRows: number
  invalidRows: number
  rows: OrderImportRow[]
}

export const orderImportService = {
  /** Multipart upload — client passes a File; we wrap in FormData. */
  preview: async (file: File): Promise<ApiResponse<OrderImportPreview>> => {
    const form = new FormData()
    form.append('file', file)
    const token = localStorage.getItem('multiship_token')
    const response = await fetch(`${BASE_URL}/orders/import/preview`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form,
    })
    const json: ApiResponse<OrderImportPreview> = await response.json()
    return json
  },

  commit: (rows: OrderImportRow[]) =>
    apiClient.post<ApiResponse<OrderImportPreview>>('/orders/import/commit', rows),

  templateUrl: () => `${BASE_URL}/orders/import/template.csv`,
}
