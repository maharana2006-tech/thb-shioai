import { apiClient, BASE_URL } from './apiClient'
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
      q: p.q ?? '', labelStatus: p.labelStatus ?? '', batchStatus: p.batchStatus ?? '',
      createdFrom: p.createdFrom ?? '', createdTo: p.createdTo ?? '',
    })
    return apiClient.get<ApiResponse<DtcBatchPage>>(`/dtc/batches?${qs}`)
  },

  /** Paged lines of one batch + per-line tracking status map. */
  batchDetail: (batchId: string, p: DtcBatchDetailQuery) => {
    const qs = new URLSearchParams({
      page: String(p.page), size: String(p.size), tenantId: p.tenantId ?? '',
      q: p.q ?? '', status: p.status ?? '', carrier: p.carrier ?? '', shipDate: p.shipDate ?? '',
    })
    return apiClient.get<ApiResponse<DtcBatchDetail>>(`/dtc/batches/${batchId}?${qs}`)
  },

  /** Enqueue "Automatic label" generation for a batch. 409 while one is active. */
  generate: (batchId: string, tenantId: string) =>
    apiClient.post<ApiResponse<{ job: DtcGenerationJob }>>(
      `/dtc/batches/${batchId}/generate?tenantId=${encodeURIComponent(tenantId)}`, {}),

  /**
   * Correct a line that has no label order yet (null = keep, "" = clear), or link it to an
   * order labelled by hand ({ adoptOrderNo }). 409 once the line has a label or is being bought.
   */
  /** One line — the manual shipment form's prefill when a failed line is fixed there. */
  line: (batchId: string, lineId: number, tenantId: string) =>
    apiClient.get<ApiResponse<DtcOrder>>(
      `/dtc/batches/${batchId}/lines/${lineId}?tenantId=${encodeURIComponent(tenantId)}`),

  editLine: (batchId: string, lineId: number, tenantId: string, body: DtcLineEdit) =>
    apiClient.patch<ApiResponse<DtcOrder>>(
      `/dtc/batches/${batchId}/lines/${lineId}?tenantId=${encodeURIComponent(tenantId)}`, body),

  /** Generation job progress (poll after enqueue). */
  generationJob: (jobId: number) =>
    apiClient.get<ApiResponse<DtcGenerationJob>>(`/dtc/batches/generation-jobs/${jobId}`),

  /**
   * All GENERATED, non-voided labels of a batch merged into ONE PDF, for the
   * print dialog (Print / Reprint). 404 when no label is ready yet — the
   * JSON body's `message` says why.
   */
  labelsPdf: async (batchId: string, tenantId: string): Promise<Blob> => {
    const res = await fetch(
      `${BASE_URL}/dtc/batches/${batchId}/labels.pdf?tenantId=${encodeURIComponent(tenantId)}`,
      { credentials: 'include' },
    )
    if (!res.ok) {
      let msg = `Labels are not ready to print (HTTP ${res.status}).`
      try { msg = (await res.json())?.message || msg } catch { /* non-JSON body */ }
      throw Object.assign(new Error(msg), { status: res.status })
    }
    return res.blob()
  },

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
  /** Generated lines whose label was since voided at the carrier (also in generatedCount). */
  voidedCount?: number
  lastSyncedAt: string | null
}

/** Labels the batch can still print — generated and not voided. */
export function printableCount(b: Pick<DtcBatchStats, 'generatedCount' | 'voidedCount'>): number {
  return b.generatedCount - (b.voidedCount ?? 0)
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
  /** "TENANT|batchId" → when any of the batch's labels was last printed (ISO). */
  lastPrinted?: Record<string, string>
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
  /** Free text over batch no, tote, order no, customer PO and ship-to name/city. */
  q?: string
  /** GENERATED | PARTIAL | PENDING — matches the badge in the Label Status column. */
  labelStatus?: string
  /** COMPLETE | OPEN — matches the badge in the Batch Status column. */
  batchStatus?: string
  /** ISO date (YYYY-MM-DD) bounds on when the batch was synced; empty = unbounded. */
  createdFrom?: string
  createdTo?: string
}

/** One dtc_orders row (DtcOrder entity, V102 generation columns included). */
/** The fields Automatic label builds a shipment from — what an operator may correct on a line. */
export type DtcLineEdit = Partial<Pick<DtcOrder,
  'shipName' | 'shipAttn' | 'shipAddr1' | 'shipAddr2' | 'shipAddr3' | 'shipToCity' | 'shipToState'
  | 'shipToZip' | 'shipToCountryCode' | 'phone' | 'email' | 'goodsDesc' | 'shipViaCode'>> & {
  weight?: number | null
  unitValue?: number | null
  adoptOrderNo?: number
}

/** A line that can be corrected here: no label order yet, and not labelled or being bought. */
export function canEditLine(o: Pick<DtcOrder, 'generatedOrderNo' | 'generatedStatus'>): boolean {
  return !o.generatedOrderNo && o.generatedStatus !== 'GENERATED'
    && o.generatedStatus !== 'QUEUED_USPS' && o.generatedStatus !== 'IN_FLIGHT'
}

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
  /** IN_FLIGHT — the label is being bought right now (stamped just before the carrier call). */
  generatedStatus: 'GENERATED' | 'FAILED' | 'QUEUED_USPS' | 'IN_FLIGHT' | null
  generatedMessage: string | null
  generatedAt: string | null
}

/**
 * True when a line carries an error the operator can correct. A GENERATED row
 * also stores the carrier's success note in generatedMessage, so the message
 * alone is not an error — only an unlabelled row reporting one is.
 */
export function lineHasError(o: Pick<DtcOrder, 'generatedStatus' | 'generatedMessage'>): boolean {
  if (o.generatedStatus === 'GENERATED' || o.generatedStatus === 'QUEUED_USPS') return false
  return o.generatedStatus === 'FAILED' || !!o.generatedMessage?.trim()
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
  /** Filter options for this batch. */
  carriers?: string[]
  shipDates?: string[]
  /** Lines per Label Status for the whole batch (NOT_GENERATED, GENERATED, FAILED, VOIDED, …). */
  statusCounts?: Record<string, number>
}

export interface DtcBatchDetailQuery {
  page: number
  size: number
  tenantId?: string
  /** Order no, label order, tote, tracking, PO or ship-to name. */
  q?: string
  /** GENERATED | QUEUED_USPS | FAILED | IN_FLIGHT | NOT_GENERATED | VOIDED */
  status?: string
  carrier?: string
  shipDate?: string
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
