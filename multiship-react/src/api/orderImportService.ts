import { apiClient, authFetch, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

export interface OrderImportRow {
  rowNumber: number
  recipientName?: string | null
  /** Sprint 48 — order-group key. Rows sharing a non-blank orderRef
   *  fold into one shipment; the first row supplies recipient/carrier/
   *  service, subsequent rows carry only orderRef + item columns. */
  orderRef?: string | null
  /** Sprint 48 — universal-template columns (serialized by the backend). */
  clientCode?: string | null
  billTo?: string | null
  warehouseCode?: string | null
  /** Tier 4 — tenant-defined custom fields, keyed by the definition's fieldKey. */
  customFields?: Record<string, string> | null
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
  /** Id shared by every order generated from this same file upload. Null until commit. */
  batchId?: number | null
}

export interface OrderImportPreview {
  totalRows: number
  validRows: number
  invalidRows: number
  /** Id shared by every order this commit generated a label for. Null on preview. */
  batchId?: number | null
  rows: OrderImportRow[]
}

/** Lifecycle status of a saved import. */
export type ImportStatus = 'DRAFT' | 'INITIATE' | 'IN_PROGRESS' | 'PARTIAL_COMPLETE' | 'COMPLETE' | 'FAILED'

/** A saved import in the Data History list. */
export interface ImportBatchSummary {
  id: number
  createdBy?: string | null
  /** Original uploaded file the rows came from. */
  fileName?: string | null
  /** INITIATE | IN_PROGRESS | PARTIAL_COMPLETE | COMPLETE. */
  status?: ImportStatus | string | null
  /** Label batch id shared by every order this import generated a label for.
   *  Null until the first label is generated. Groups the orders in All Orders. */
  labelBatchId?: number | null
  createdAt?: string | null
  totalRows: number
  savedRows: number
  invalidRows: number
  /** Soft-delete timestamp (ISO). Null = live; non-null = in Trash. */
  deletedAt?: string | null
  /** User who moved this batch to Trash. */
  deletedBy?: string | null
  /** Bill-to account mode: 'AUTO' (cascade) or 'PLATFORM' (house account). */
  billingMode?: 'AUTO' | 'PLATFORM' | string | null
  /** Origin of the rows: 'BULK' (uploaded file) or 'WMS' (Fetch from WMS).
   *  WMS batches are a read-only record of a fetch — labels are generated in
   *  the Shipments workspace, so Generate/Retry are hidden for them. */
  source?: 'BULK' | 'WMS' | string | null
}

/** A saved import with its full rows (detail view). */
export interface ImportBatchDetail extends ImportBatchSummary {
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

  /** Save the previewed rows to Data History (persists the data, no labels).
   *  `fileName` is recorded so the history row shows where the data came from.
   *  `draft` parks the batch even with invalid rows; a final save (draft=false)
   *  is rejected 422 unless every row is valid. */
  save: (rows: OrderImportRow[], fileName?: string | null, draft = false) => {
    const params = new URLSearchParams()
    if (fileName) params.set('fileName', fileName)
    if (draft) params.set('draft', 'true')
    const qs = params.toString()
    return apiClient.post<ApiResponse<OrderImportPreview>>(
      `/orders/import/save${qs ? `?${qs}` : ''}`,
      rows,
    )
  },

  /** List saved imports for the Data History page. Pass `deleted` for Trash. */
  listHistory: (deleted = false) =>
    apiClient.get<ApiResponse<ImportBatchSummary[]>>(
      `/orders/import/history${deleted ? '?deleted=true' : ''}`,
    ),

  /** One saved import with its full rows. */
  getHistory: (id: number) =>
    apiClient.get<ApiResponse<ImportBatchDetail>>(`/orders/import/history/${id}`),

  /** Soft-delete an import batch — moves it to Trash (recoverable). */
  deleteBatch: (id: number) =>
    apiClient.delete<ApiResponse<ImportBatchDetail>>(`/orders/import/history/${id}`),

  /** Restore a soft-deleted import batch from Trash. */
  restoreBatch: (id: number) =>
    apiClient.post<ApiResponse<ImportBatchDetail>>(`/orders/import/history/${id}/restore`, {}),

  /** Empty the Trash — PERMANENTLY delete every soft-deleted import. Irreversible.
   *  Resolves with the number of imports purged. */
  emptyTrash: () =>
    apiClient.delete<ApiResponse<number>>('/orders/import/history/trash'),

  /** Set a batch's bill-to account mode ('AUTO' | 'PLATFORM'). Persisted. */
  setBillingMode: (id: number, mode: 'AUTO' | 'PLATFORM') =>
    apiClient.put<ApiResponse<ImportBatchDetail>>(
      `/orders/import/history/${id}/billing-mode?mode=${mode}`,
      {},
    ),

  /**
   * Generate carrier labels for a saved batch — advances its status
   * INITIATE → IN_PROGRESS → COMPLETE / PARTIAL_COMPLETE.
   *
   * - onlyFailed=true (Sprint 55 audit #302 F3.2) skips rows already GENERATED
   *   when RETRYING a PARTIAL_COMPLETE batch — no duplicate carrier calls/billing.
   * - usePlatformAccount=true forces the platform (house) account for every row.
   */
  generateLabels: (id: number, opts?: { onlyFailed?: boolean; usePlatformAccount?: boolean }) =>
    apiClient.post<ApiResponse<ImportBatchDetail>>(
      `/orders/import/history/${id}/generate${(() => {
        const p: string[] = []
        if (opts?.onlyFailed) p.push('onlyFailed=true')
        if (opts?.usePlatformAccount) p.push('usePlatformAccount=true')
        return p.length ? `?${p.join('&')}` : ''
      })()}`,
      {},
    ),

  /** Generate a carrier label for a single row of a saved batch. */
  generateRowLabel: (id: number, rowNumber: number) =>
    apiClient.post<ApiResponse<ImportBatchDetail>>(
      `/orders/import/history/${id}/generate/${rowNumber}`,
      {},
    ),

  /**
   * Sprint 51 — correct one row of a saved import in place. The backend
   * re-validates the whole batch, re-stamps each ungenerated row
   * SAVED / NEEDS_FIX, and returns the refreshed batch (counts + status
   * updated). Rows that already have a label are immutable.
   */
  updateRow: (id: number, rowNumber: number, row: OrderImportRow) =>
    apiClient.put<ApiResponse<ImportBatchDetail>>(
      `/orders/import/history/${id}/rows/${rowNumber}`,
      row,
    ),

  /**
   * Sprint 48 — re-validate rows the operator edited in the preview
   * table without re-uploading the file. Backend re-runs required-field
   * checks, name→code resolution, and the international-item rule.
   * Errors + warnings on each row are refreshed in place.
   */
  validate: (rows: OrderImportRow[]) =>
    apiClient.post<ApiResponse<OrderImportPreview>>('/orders/import/validate', rows),

  /**
   * Sprint 48 — address-validate every row against its picked carrier's
   * own address-validation API (UPS / FedEx / USPS / DHL). Invalid
   * addresses append a NON-FATAL warning; errors list stays untouched
   * so operators can commit whether or not addresses check out.
   */
  validateAddresses: (rows: OrderImportRow[]) =>
    apiClient.post<ApiResponse<OrderImportPreview>>('/orders/import/validate-addresses', rows),

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
