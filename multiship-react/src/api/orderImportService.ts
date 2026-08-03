import { apiClient, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

export interface OrderImportRow {
  rowNumber: number
  recipientName?: string | null
  /** Sprint 48 — order-group key. Rows sharing a non-blank orderRef
   *  fold into one shipment; the first row supplies recipient/carrier/
   *  service, subsequent rows carry only orderRef + item columns. */
  orderRef?: string | null
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
  /** Sprint 41 — bill-to carrier account. Optional at import time. */
  accountNumber?: string | null
  serviceType?: string | null
  packageType?: string | null
  weight?: number | null
  weightUnit?: string | null
  declaredValue?: number | null
  currency?: string | null
  reference?: string | null
  goodsDescription?: string | null
  // Sprint 48 — per-item customs fields.
  itemDescription?: string | null
  itemSku?: string | null
  itemQuantity?: number | null
  itemUnitValue?: number | null
  hsCode?: string | null
  countryOfOrigin?: string | null
  errors: string[]
  /** Sprint 48 — non-fatal warnings surfaced on preview (e.g. account
   *  divergence). Committing rows with warnings is allowed. */
  warnings?: string[] | null
  /** Sprint 41 — populated on commit only. GENERATED = label created;
   *  FAILED = carrier or downstream failure (see generatedMessage). */
  generatedOrderNo?: number | null
  generatedTrackingNumber?: string | null
  generatedStatus?: 'GENERATED' | 'FAILED' | null
  generatedMessage?: string | null
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

  /**
   * Sprint 48 — download the richer .xlsx template with data-validation
   * dropdowns + sample rows. Requires auth (unlike the public .csv
   * template), so we fetch it as a Blob with the Bearer token attached
   * and trigger a browser download via an object URL.
   *
   * When `accountId` is supplied, the download URL includes it and the
   * generated workbook is scoped to that carrier account (accountNumber
   * prefilled, carrier locked, service / package dropdowns narrowed).
   * Null accountId = generic template.
   */
  downloadXlsxTemplate: async (accountId?: number | null): Promise<void> => {
    const token = localStorage.getItem('multiship_token')
    const qs = accountId != null ? `?accountId=${accountId}` : ''
    const response = await fetch(`${BASE_URL}/orders/import/template.xlsx${qs}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) {
      throw new Error(`Template download failed (HTTP ${response.status})`)
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const filename = accountId != null
      ? `order-import-template-account-${accountId}.xlsx`
      : 'order-import-template-generic.xlsx'
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    // Revoke after a short delay so the download tab has time to fire.
    window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
  },
}
