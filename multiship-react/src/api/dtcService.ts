import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * D2C History — two-level flow over the dtc_orders table (Oracle sync):
 *
 *   summary  GET /dtc/batches                one row per (tenant, batch)
 *   detail   GET /dtc/batches/{batchId}      paged lines + voidStatuses
 *   generate POST /dtc/batches/{batchId}/generate   → dtc_generation_job
 *   poll     GET /dtc/batches/generation-jobs/{id}
 *   zip      GET /dtc/batches/{batchId}/labels.zip
 *
 * Print/Void per line reuse the order endpoints
 * (/api/v1/orders/{orderNo}/label/pdf, POST /api/v1/orders/{orderNo}/void)
 * against the generated order number.
 */
export const dtcService = {
  /** Batch summary page — distinct (tenant, batch) rows with aggregates. */
  batches: (p: DtcBatchQuery) => {
    const qs = new URLSearchParams({
      page: String(p.page), size: String(p.size),
      tenantId: p.tenantId ?? '', shipDate: p.shipDate ?? '',
    })
    return apiClient.get<ApiResponse<DtcBatchPage>>(`/dtc/batches?${qs}`)
  },

  /** Paged lines of one batch + per-line tracking status map. */
  batchDetail: (batchId: string, p: DtcBatchDetailQuery) => {
    const qs = new URLSearchParams({
      page: String(p.page), size: String(p.size), tenantId: p.tenantId ?? '',
    })
    return apiClient.get<ApiResponse<DtcBatchDetail>>(`/dtc/batches/${batchId}?${qs}`)
  },

  /** Enqueue "Automatic label" generation for a batch. 409 while one is active. */
  generate: (batchId: string, tenantId: string) =>
    apiClient.post<ApiResponse<{ job: DtcGenerationJob }>>(
      `/dtc/batches/${batchId}/generate?tenantId=${encodeURIComponent(tenantId)}`, {}),

  /** Generation job progress (poll after enqueue). */
  generationJob: (jobId: number) =>
    apiClient.get<ApiResponse<DtcGenerationJob>>(`/dtc/batches/generation-jobs/${jobId}`),

  /** Batch label ZIP download URL (all GENERATED rows' PDFs). */
  labelsZipUrl: (batchId: string, tenantId: string) =>
    `/api/v1/dtc/batches/${batchId}/labels.zip?tenantId=${encodeURIComponent(tenantId)}`,

  /** Label PDF for one generated order — the order endpoint, verbatim. */
  labelPdfUrl: (orderNo: number) => `/api/v1/orders/${orderNo}/label/pdf`,

  /** Distinct ship dates for the summary-page date filter. */
  shipDates: () => apiClient.get<ApiResponse<string[]>>('/dtc/batches/ship-dates'),

  /** Tenants present in dtc_orders (client filter options). */
  orderTenants: () => apiClient.get<ApiResponse<string[]>>('/dtc/orders/tenants'),

  /** Copy pending orders from the Oracle view into dtc_orders. Empty tenant = all. */
  syncOracle: (tenantId = '') =>
    apiClient.post<ApiResponse<DtcSyncResult>>(`/dtc/sync/oracle?tenantId=${encodeURIComponent(tenantId)}`, {}),
}

/**
 * Aggregate over one (tenant, batch) — DtcBatchStats record.
 * `batchStatus` / `labelStatus` are computed server-side record methods and do
 * NOT appear in the JSON — derive them from the counts with the helpers below.
 */
export interface DtcBatchStats {
  tenantId: string
  batchId: number
  totalLines: number
  startOrderNo: number | null
  endOrderNo: number | null
  firstTote: string | null
  lastTote: string | null
  shipDate: string | null
  generatedCount: number
  failedCount: number
  queuedCount: number
  pendingCount: number
  lastSyncedAt: string | null
}

export function batchStatusOf(b: Pick<DtcBatchStats, 'pendingCount' | 'queuedCount'>): 'COMPLETE' | 'OPEN' {
  return b.pendingCount + b.queuedCount === 0 ? 'COMPLETE' : 'OPEN'
}

export function labelStatusOf(b: Pick<DtcBatchStats, 'totalLines' | 'generatedCount' | 'failedCount' | 'queuedCount'>): string {
  if (!b.totalLines) return 'NONE'
  if (b.generatedCount + b.queuedCount >= b.totalLines) return 'GENERATED'
  if (b.generatedCount + b.queuedCount + b.failedCount === 0) return 'PENDING'
  return 'PARTIAL'
}

export interface DtcBatchPage {
  content: DtcBatchStats[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
}

export interface DtcBatchQuery {
  page: number
  size: number
  tenantId?: string
  shipDate?: string
}

/** One dtc_orders row (DtcOrder entity, V102 generation columns included). */
export interface DtcOrder {
  id: number
  batchId: number
  orderNo: number | null
  orderSuffix: number | null
  orderStatus: string | null
  custNo: string | null
  custPo: string | null
  tenantId: string
  shipViaCode: string | null
  shipVia: string | null
  termsCode: string | null
  shipName: string | null
  shipAttn: string | null
  shipAddr1: string | null
  shipAddr2: string | null
  shipAddr3: string | null
  shipToCity: string | null
  shipToState: string | null
  shipToZip: string | null
  shipToCountryCode: string | null
  countryName: string | null
  phone: string | null
  email: string | null
  weight: number | null
  unitValue: number | null
  price: string | null
  freightCost: number | null
  goodsDesc: string | null
  intlYn: string | null
  toteNumber: string
  location: string | null
  track: string | null
  thirdPartyAccount: string | null
  shipDate: string | null
  ffSchemaSubstr: string | null
  createdAt: string | null
  generatedOrderNo: number | null
  generatedTrackingNumber: string | null
  generatedCarrierCode: string | null
  generatedStatus: 'GENERATED' | 'FAILED' | 'QUEUED_USPS' | null
  generatedMessage: string | null
  generatedAt: string | null
}

/** GET /dtc/batches/{batchId} body. */
export interface DtcBatchDetail {
  content: DtcOrder[]
  /** orderNo → order_label_tracking.status (GENERATED / VOIDED / …). */
  voidStatuses: Record<string, string>
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
  batch?: DtcBatchStats
}

export interface DtcBatchDetailQuery {
  page: number
  size: number
  tenantId?: string
}

/** dtc_generation_job row — the FE polls this while a run is active. */
export interface DtcGenerationJob {
  id: number
  tenantId: string
  batchId: number
  status: 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'
  totalRows: number
  processedRows: number
  generatedCount: number
  failedCount: number
  skippedCount: number
  errorMessage: string | null
  requestedBy: string | null
  queuedAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface DtcSyncResult {
  fetched: number
  imported: number
  skipped: number
  message: string
}
