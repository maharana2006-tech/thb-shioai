import { apiClient, authFetch, BASE_URL } from './apiClient'
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
  /**
   * Multipart upload — client passes a File; we wrap in FormData.
   * `expectedAccountId`, when the operator downloaded a scoped .xlsx
   * template first, drives the backend's per-row divergence warning
   * (any row whose accountNumber differs gets a non-fatal warning).
   */
  preview: async (
    file: File,
    expectedAccountId?: number | null,
  ): Promise<ApiResponse<OrderImportPreview>> => {
    const form = new FormData()
    form.append('file', file)
    const qs = expectedAccountId != null ? `?expectedAccountId=${expectedAccountId}` : ''
    // authFetch attaches the Bearer token + surfaces the actual server
    // error message on non-2xx (previously the caller got a raw JSON
    // parse failure when Security returned 401 as HTML). 401 also
    // auto-kicks to /login so an expired JWT doesn't stall the operator.
    const response = await authFetch(`/orders/import/preview${qs}`, {
      method: 'POST',
      body: form,
    })
    return (await response.json()) as ApiResponse<OrderImportPreview>
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
    const qs = accountId != null ? `?accountId=${accountId}` : ''
    // authFetch = Bearer attached + response-body-aware error message +
    // auto-logout on 401. Previously threw "HTTP 401" with no server
    // context, which hid expired-JWT vs missing-token vs role-denied.
    const response = await authFetch(`/orders/import/template.xlsx${qs}`)
    const blob = await response.blob()
    // Backend switches Content-Type to macroEnabled.12 + filename to
    // .xlsm when the static resource is present; grab the filename
    // from Content-Disposition when we can, else use our default.
    const cd = response.headers.get('Content-Disposition') || ''
    const nameMatch = /filename="?([^"]+)"?/i.exec(cd)
    const filename = nameMatch
      ? nameMatch[1]
      : accountId != null
        ? `order-import-template-account-${accountId}.xlsx`
        : 'order-import-template-generic.xlsx'
    const url = URL.createObjectURL(blob)
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
